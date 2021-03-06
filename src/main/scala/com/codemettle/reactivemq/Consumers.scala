/*
 * Consumers.scala
 *
 * Updated: Feb 16, 2015
 *
 * Copyright (c) 2015, CodeMettle
 */
package com.codemettle.reactivemq

import java.util.UUID

import com.codemettle.reactivemq.QueueConsumer.QueueConsumerSubscriber
import com.codemettle.reactivemq.ReActiveMQMessages._
import com.codemettle.reactivemq.TopicConsumer.TopicConsumerSubscriber
import com.codemettle.reactivemq.config.ReActiveMQConfig
import com.codemettle.reactivemq.model.{AMQMessage, Destination, Queue, Topic}

import akka.actor._
import scala.concurrent.duration._

/**
 * @author steven
 *
 */
object QueueConsumer {
    private class ResponderActor(connectionActor: ActorRef) extends Actor with ActorLogging {
        import context.dispatcher

        private var timer = Option.empty[Cancellable]

        private var replyToOrOrigMsg: Either[AMQMessage, Destination] = _
        private var messageId = Option.empty[String]
        private var correlationId = Option.empty[String]

        override def postStop() = {
            super.postStop()

            timer foreach (_.cancel())
        }

        private def sendResponseToOriginator(reply: AMQMessage) = {
            replyToOrOrigMsg match {
                case Left(msg) =>
                    log.warning("No JMSReplyTo on {}, can't reply with {}", msg, reply)
                    context stop self

                case Right(replyTo) =>
                    // http://docs.oracle.com/cd/E13171_01/alsb/docs25/interopjms/MsgIDPatternforJMS.html
                    //  fallback to msgId
                    val responseCorrId = correlationId orElse messageId
                    val replyMsg = responseCorrId.fold(reply)(reply.withCorrelationID)

                    connectionActor ! SendMessage(replyTo, replyMsg)

                    context become sentMessage(reply)
            }
        }

        private def configureResponder(msgToReplyTo: AMQMessage, defaultTimeout: FiniteDuration) = {
            def timeUntilExpiration = {
                val now = System.currentTimeMillis()
                if (msgToReplyTo.properties.expiration > now)
                    (msgToReplyTo.properties.expiration - now).millis
                else
                    defaultTimeout
            }

            timer = Some(context.system.scheduler.scheduleOnce(timeUntilExpiration, self, Expired))

            messageId = msgToReplyTo.properties.messageID
            correlationId = msgToReplyTo.properties.correlationID
            replyToOrOrigMsg = msgToReplyTo.properties.replyTo toRight msgToReplyTo
        }

        def receive = {
            case MessageForResponse(msg, defaultTimeout) => configureResponder(msg, defaultTimeout)
            case Expired => context stop self
            case message: AMQMessage => sendResponseToOriginator(message)
            case message => sendResponseToOriginator(AMQMessage(message))
        }

        def sentMessage(reply: AMQMessage): Receive = {
            case Expired => context stop self
            case SendAck =>
                log.debug("Response sent")
                context stop self

            case Status.Failure(t) =>
                log.error(t, "Error sending {}", reply)
                context stop self
        }
    }

    private object ResponderActor {
        def props(connectionActor: ActorRef) = {
            Props(new ResponderActor(connectionActor))
        }
    }

    private class QueueConsumerSubscriber(connectionActor: ActorRef, dest: Queue, sendStatusNotifs: Boolean,
                                          noExpirationReplyTimeout: FiniteDuration, oneway: Boolean) extends Actor {
        override def preStart() = {
            super.preStart()

            subscribe()
        }

        private def subscribe() = {
            connectionActor ! Consume(dest, sharedConsumer = false)
        }

        private lazy val requestProps = ResponderActor props connectionActor

        private def createResponder(forMessage: AMQMessage): ActorRef = {
            val msgId = forMessage.properties.messageID getOrElse UUID.randomUUID().toString

            val actor = context.actorOf(requestProps, s"responder-$msgId")

            actor ! MessageForResponse(forMessage, noExpirationReplyTimeout)

            actor
        }

        private def getReplyActor(forMessage: AMQMessage): ActorRef = {
            if (oneway)
                Actor.noSender
            else
                createResponder(forMessage)
        }

        def receive = {
            case cf: ConsumeFailed =>
                subscribe()
                if (sendStatusNotifs) context.parent forward cf

            case notif: DedicatedConsumerNotif => if (sendStatusNotifs) context.parent forward notif

            case msg: AMQMessage => context.parent.tell(msg, getReplyActor(msg))
        }
    }

    private object QueueConsumerSubscriber {
        def props(connectionActor: ActorRef, dest: Queue, sendStatusNotifs: Boolean,
                  noExpirationReplyTimeout: FiniteDuration, oneway: Boolean) = {
            Props(new QueueConsumerSubscriber(connectionActor, dest, sendStatusNotifs, noExpirationReplyTimeout, oneway))
        }
    }

    private case class MessageForResponse(msg: AMQMessage, defaultTimeout: FiniteDuration)
    private case object Expired
}

trait QueueConsumer extends Actor with TwoWayCapable {
    private val config = ReActiveMQConfig(context.system)

    context.actorOf(QueueConsumerSubscriber.props(connection, consumeFrom, receiveConsumeNotifications,
        noExpirationReplyTimeout, oneway), "sub")

    def connection: ActorRef
    def consumeFrom: Queue

    /**
     * Override in an individual consumer to ignore the configured default
     */
    protected def noExpirationReplyTimeout: FiniteDuration = config.queueConsumerTimeout

    /**
     * Override in an individual consumer to receive ConsumeSuccess and ConsumeFailed messages
     */
    protected def receiveConsumeNotifications: Boolean = false
}

object TopicConsumer {

    private class TopicConsumerSubscriber(connectionActor: ActorRef, dest: Topic) extends Actor {
        override def preStart() = {
            super.preStart()

            connectionActor ! Consume(dest, sharedConsumer = true)
        }

        def receive = {
            case msg: AMQMessage => context.parent forward msg
        }
    }

    private object TopicConsumerSubscriber {
        def props(connection: ActorRef, dest: Topic) = {
            Props(new TopicConsumerSubscriber(connection, dest))
        }
    }

}

trait TopicConsumer extends Actor {
    def connection: ActorRef
    def consumeFrom: Topic

    context.actorOf(TopicConsumerSubscriber.props(connection, consumeFrom), "sub")
}
