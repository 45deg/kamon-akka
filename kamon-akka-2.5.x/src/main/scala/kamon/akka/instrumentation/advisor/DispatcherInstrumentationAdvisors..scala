/*
 * =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package akka.kamon.instrumentation.advisor

import java.lang.reflect.Method
import java.util.concurrent.ExecutorService

import akka.actor.{ActorContext, ActorSystem, Props}
import akka.dispatch.ForkJoinExecutorConfigurator.AkkaForkJoinPool
import akka.dispatch.{Dispatcher, _}
import akka.kamon.instrumentation.advisor.DispatcherInstrumentationAdvisors._
import akka.kamon.instrumentation.advisor.LookupDataAware.LookupData
import kamon.{Kamon, Tags}
import kamon.agent.libs.net.bytebuddy.asm.Advice
import kamon.agent.libs.net.bytebuddy.asm.Advice._
import kamon.akka.Akka
import kamon.akka.instrumentation.mixin.{ActorSystemAware, LookupDataAware}
import kamon.executors.{Executors, Metrics}
import kamon.executors.Executors.ExecutorSampler
import kamon.util.Registration

import scala.collection.concurrent.TrieMap

/**
  * Advisor for akka.actor.ActorSystemImpl::start
  */
class StartMethodAdvisor
object StartMethodAdvisor {
  @OnMethodEnter
  def onEnter(@This system: ActorSystem): Unit = {
    system.dispatchers.asInstanceOf[ActorSystemAware].actorSystem = system

    // The default dispatcher for the actor system is looked up in the ActorSystemImpl's initialization code and we
    // can't get the Metrics extension there since the ActorSystem is not yet fully constructed. To workaround that
    // we are manually selecting and registering the default dispatcher with the Metrics extension. All other dispatchers
    // will by registered by the instrumentation bellow.

    // Yes, reflection sucks, but this piece of code is only executed once on ActorSystem's startup.
    val defaultDispatcher = system.dispatcher
    val defaultDispatcherExecutor = extractExecutor(defaultDispatcher.asInstanceOf[MessageDispatcher])
    registerDispatcher(Dispatchers.DefaultDispatcherId, defaultDispatcherExecutor, system)
  }
}

/**
  * Advisor for akka.dispatch.Dispatchers::lookup
  */
class LookupMethodAdvisor
object LookupMethodAdvisor {
  @OnMethodEnter
  def onEnter(@This dispatchers: ActorSystemAware, @Argument(0) dispatcherName: String): ThreadLocal[LookupData] = {
    LookupDataAware.setLookupData(LookupData(dispatcherName, dispatchers.actorSystem))
  }

  @OnMethodExit
  def onExit(@Enter lookupData: ThreadLocal[LookupData]): Unit = lookupData.remove()
}

/**
  * Advisor for akka.dispatch.ExecutorServiceFactory+::constructor
  */
class ExecutorServiceFactoryConstructorAdvisor
object ExecutorServiceFactoryConstructorAdvisor {
  @OnMethodExit
  def onExit(@This factory: ExecutorServiceFactory): Unit = {
    factory.asInstanceOf[LookupDataAware].lookupData = LookupDataAware.currentLookupData
  }
}

/**
  * Advisor for akka.dispatch.ExecutorServiceFactory+::createExecutorService
  */
class CreateExecutorServiceAdvisor
object CreateExecutorServiceAdvisor {
  @OnMethodExit
  def onExit(@This factory: ExecutorServiceFactory, @Return executorService: ExecutorService): Unit = {
    val lookupData = factory.asInstanceOf[LookupDataAware].lookupData

    // lookupData.actorSystem will be null only during the first lookup of the default dispatcher during the
    // ActorSystemImpl's initialization.
    if (lookupData.actorSystem != null)
      registerDispatcher(lookupData.dispatcherName, executorService, lookupData.actorSystem)
  }
}

/**
  * Advisor for akka.dispatch.Dispatcher.LazyExecutorServiceDelegate::constructor
  */
class LazyExecutorServiceDelegateConstructorAdvisor
object LazyExecutorServiceDelegateConstructorAdvisor {
  @OnMethodExit
  def onExit(@This lazyExecutor: ExecutorServiceDelegate): Unit = {
    lazyExecutor.asInstanceOf[LookupDataAware].lookupData = LookupDataAware.currentLookupData
  }
}

/**
  * Advisor for akka.dispatch.Dispatcher.LazyExecutorServiceDelegate::copy
  */
class CopyMethodAdvisor
object CopyMethodAdvisor {
  @OnMethodEnter
  def onEnter(@This lazyExecutor: ExecutorServiceDelegate): ThreadLocal[LookupData] = {
    LookupDataAware.setLookupData(lazyExecutor.asInstanceOf[LookupDataAware].lookupData)
  }

  @OnMethodExit
  def onExit(@Enter lookupData: ThreadLocal[LookupData]): Unit = lookupData.remove()
}

/**
  * Advisor for akka.dispatch.Dispatcher.LazyExecutorServiceDelegate::shutdown
  */
class ShutdownMethodAdvisor
object ShutdownMethodAdvisor {
  @OnMethodExit
  def onExit(@This lazyExecutor: ExecutorServiceDelegate): Unit = {
    import DispatcherInstrumentationAdvisors._
    val lookupData = lazyExecutor.asInstanceOf[LookupDataAware].lookupData

    if (lookupData.actorSystem != null) {
      registeredDispatchers.get(lookupData.dispatcherName).foreach(_.cancel())
    }
  }
}

/**
  * Advisor for a akka.routing.BalancingPool::newRoutee
  */
class NewRouteeMethodAdvisor
object NewRouteeMethodAdvisor {
  @OnMethodEnter
  def onEnter(@Advice.Argument(0) props: Props, @Advice.Argument(1) context: ActorContext): ThreadLocal[LookupData] = {
    val deployPath = context.self.path.elements.drop(1).mkString("/", "/", "")
    val dispatcherId = s"BalancingPool-$deployPath"

    LookupDataAware.setLookupData(LookupData(dispatcherId, context.system))
  }

  @OnMethodExit
  def onExit(@Enter lookupData: ThreadLocal[LookupData]): Unit = lookupData.remove()
}

object LookupDataAware {
  case class LookupData(dispatcherName: String, actorSystem: ActorSystem)

  private val _currentDispatcherLookupData = new ThreadLocal[LookupData]

  def apply() = new LookupDataAware {}

  def currentLookupData: LookupData = _currentDispatcherLookupData.get()

  def setLookupData[T](lookupData: LookupData): ThreadLocal[LookupData] = {
    _currentDispatcherLookupData.set(lookupData)
    _currentDispatcherLookupData
  }
}



object AkkaDispatcherMetrics {
  val Category = "akka-dispatcher"
}

object DispatcherInstrumentationAdvisors {

  val registeredDispatchers: TrieMap[String, Registration] = TrieMap.empty[String, Registration]

  def extractExecutor(dispatcher: MessageDispatcher): ExecutorService = {
    val executorServiceMethod: Method = {
      // executorService is protected
      val method = classOf[Dispatcher].getDeclaredMethod("executorService")
      method.setAccessible(true)
      method
    }

    dispatcher match {
      case x: Dispatcher ⇒
        val executor = executorServiceMethod.invoke(x) match {
          case delegate: ExecutorServiceDelegate ⇒ delegate.executor
          case other                             ⇒ other
        }
        executor.asInstanceOf[ExecutorService]
    }
  }

  def registerDispatcher(dispatcherName: String, executorService: ExecutorService, system: ActorSystem): Unit = {
    if(Kamon.filter(Akka.DispatcherFilterName, dispatcherName)) {
      val additionalTags = Map("actor-system" -> system.name)
      val dispatcherRegistration = executorService match {
        case afjp: AkkaForkJoinPool => Executors.register(dispatcherName, additionalTags, akkaForkJoinPoolSampler(dispatcherName, additionalTags, afjp))
        case other                  => Executors.register(dispatcherName, additionalTags, other)
      }

      registeredDispatchers.put(dispatcherName, dispatcherRegistration)
    }
  }

  private def akkaForkJoinPoolSampler(name: String, tags: Tags, pool: AkkaForkJoinPool): ExecutorSampler = new ExecutorSampler {
    val poolMetrics = Metrics.forForkJoinPool(name, tags)

    def sample(): Unit = {
      poolMetrics.parallelism.set(pool.getParallelism)
      poolMetrics.activeThreads.record(pool.getActiveThreadCount)
      poolMetrics.poolSize.record(pool.getPoolSize)
      poolMetrics.queuedTasks.record(pool.getQueuedTaskCount)
      poolMetrics.runningThreads.record(pool.getRunningThreadCount)
      poolMetrics.submittedTasks.record(pool.getQueuedSubmissionCount)
    }

    def cleanup(): Unit =
      poolMetrics.cleanup()
  }
}