package com.ergodicity.engine.strategy

import akka.actor.FSM.CurrentState
import akka.actor.FSM.Transition
import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.event.Logging
import akka.testkit._
import akka.util.duration._
import com.ergodicity.core.SessionsTracking.FutSessContents
import com.ergodicity.core.SessionsTracking.OngoingSession
import com.ergodicity.core.SessionsTracking.OngoingSessionTransition
import com.ergodicity.core.SessionsTracking.SubscribeOngoingSessions
import com.ergodicity.core._
import session.InstrumentParameters.{FutureParameters, Limits}
import com.ergodicity.core.session._
import com.ergodicity.engine.strategy.InstrumentWatchDog.Catched
import com.ergodicity.engine.strategy.InstrumentWatchDog.CatchedState
import com.ergodicity.engine.strategy.InstrumentWatchDog.WatchDogConfig
import org.scalatest.{WordSpec, BeforeAndAfterAll, GivenWhenThen}

class InstrumentWatchDogSpec extends TestKit(ActorSystem("InstrumentWatchDogSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  implicit val config = WatchDogConfig(self)

  val sessionId = SessionId(100, 100)
  val isinId = IsinId(166911)
  val isin = Isin("GMKR-6.12")
  val shortIsin = ShortIsin("GMM2")
  val security = FutureContract(isinId, isin, shortIsin, "Future contract")

  "InstrumentWatchDog" must {
    "subscribe for ongoing session" in {
      val instrumentData = TestProbe()
      val watchdog = TestFSMRef(new InstrumentWatchDog(isin, instrumentData.ref), "InstrumentWatchDog")

      instrumentData.expectMsg(SubscribeOngoingSessions(watchdog))
    }

    "catch instument" in {
      val instrumentData = TestProbe()
      val watchdog = TestFSMRef(new InstrumentWatchDog(isin, instrumentData.ref), "InstrumentWatchDog")

      watchdog ! OngoingSession(sessionId, buildSessionActor(sessionId))

      val catched = receiveOne(200.millis)
      assert(catched match {
        case catched: Catched =>
          log.info("Catched = " + catched)
          true
        case _ => false
      })

      watchdog.stop()
    }

    "fail watching instument that is not assigned" in {
      val instrumentData = TestProbe()

      val guard = TestActorRef(new Actor {
        val watchdog = context.actorOf(Props(new InstrumentWatchDog(Isin("NotAssignedIsin"), instrumentData.ref)(config.copy(reportTo = system.deadLetters))), "InstrumentWatchDog")
        watchdog ! OngoingSession(sessionId, buildSessionActor(sessionId))


        override def supervisorStrategy() = AllForOneStrategy() {
          case _: InstrumentWatcherException => Stop
        }

        protected def receive = null
      })

      watch(guard.underlyingActor.watchdog)
      expectMsg(Terminated(guard.underlyingActor.watchdog))

      guard.stop()
    }

    "catch new instrument on session reassigned" in {
      val instrumentData = TestProbe()
      val watchdog = TestFSMRef(new InstrumentWatchDog(isin, instrumentData.ref), "InstrumentWatchDog")

      val oldSession = OngoingSession(sessionId, buildSessionActor(sessionId))
      watchdog ! oldSession
      expectMsgType[Catched]

      val newId = SessionId(101, 101)
      val newSession = OngoingSession(newId, buildSessionActor(newId))
      watchdog ! OngoingSessionTransition(oldSession, newSession)
      expectMsgType[Catched]

      watchdog.stop()
    }

    "fail on catched instrument terminated" in {
      val instrumentData = TestProbe()

      val guard = TestActorRef(new Actor {
        val watchdog = context.actorOf(Props(new InstrumentWatchDog(isin, instrumentData.ref)), "InstrumentWatchDog")
        watchdog ! OngoingSession(sessionId, buildSessionActor(sessionId))

        val catched = receiveOne(100.millis).asInstanceOf[Catched]
        watchdog ! Terminated(catched.instrument.instrumentActor)

        override def supervisorStrategy() = AllForOneStrategy() {
          case _: InstrumentWatcherException => Stop
        }

        protected def receive = null
      })

      watch(guard.underlyingActor.watchdog)
      expectMsg(Terminated(guard.underlyingActor.watchdog))

      guard.stop()
    }

    "notify on catched instrument states" in {
      val instrumentData = TestProbe()
      val watchdog = TestFSMRef(new InstrumentWatchDog(isin, instrumentData.ref)(config.copy(notifyOnState = true)), "InstrumentWatchDog")

      watchdog ! OngoingSession(sessionId, buildSessionActor(sessionId))

      val catched = receiveOne(500.millis).asInstanceOf[Catched]
      expectMsg(CatchedState(isin, InstrumentState.Suspended))

      watchdog ! CurrentState(catched.instrument.instrumentActor, InstrumentState.Assigned)
      expectMsg(CatchedState(isin, InstrumentState.Assigned))

      watchdog ! Transition(catched.instrument.instrumentActor, InstrumentState.Assigned, InstrumentState.Online)
      expectMsg(CatchedState(isin, InstrumentState.Online))

      watchdog.stop()
    }
  }

  private def buildSessionActor(id: SessionId) = {
    val session = Session(id, null, None, None, null)
    val sessionActor = TestActorRef(new SessionActor(session), "Session")
    sessionActor ! SessionState.Online
    sessionActor ! FutSessContents(id.fut, security, FutureParameters(100, Limits(100, 100)), InstrumentState.Suspended)
    Thread.sleep(300)
    sessionActor
  }


}
