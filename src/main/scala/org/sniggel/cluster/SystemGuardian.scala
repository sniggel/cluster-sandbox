package org.sniggel.cluster

import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.ClusterDowningReason
import akka.actor.typed.{ActorRef, Behavior, Props, Terminated}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed._
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardingEnvelope}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef, ShardedEntity}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.Materializer
import akka.stream.typed.scaladsl.ActorMaterializer
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.ExecutionContext

object SystemGuardian extends Logging {

  import akka.actor.typed.scaladsl.adapter._

  def apply(): Behavior[SelfUp] =
    Behaviors.setup { context =>
      val system = context.system

      Cluster(context.system).subscriptions ! Subscribe(context.self, classOf[SelfUp])

      // Initiate SelfUp by sending a message to self
      context.self ! SelfUp(Cluster(context.system).state)
      Behaviors.receiveMessage[SelfUp] { _ =>
        val sharding: ClusterSharding = ClusterSharding(system)
        sharding.defaultShardAllocationStrategy(ClusterShardingSettings.apply(system))

        // Create account entity shard
        accountEntityShard(sharding, context.system.toUntyped)

        val accounts: EntityRef[AccountEntity.Command] =
          ClusterSharding(system).entityRefFor(AccountEntity.ShardingTypeName, AccountEntity.EntityId)

        startApi(context.system.toUntyped, accounts)
        Behaviors.empty
      }
      .receiveSignal {
        case (_, Terminated(_)) =>
          CoordinatedShutdown(context.system.toUntyped)
            .run(ClusterDowningReason)
          Behaviors.same
      }
    }

  private def startApi(system: akka.actor.ActorSystem,
                       accounts: EntityRef[AccountEntity.Command]): Unit = {
    implicit val untypedSystem: akka.actor.ActorSystem = system
    implicit val mat: Materializer = ActorMaterializer()(system.toTyped)
    implicit val readJournal: CassandraReadJournal =
      PersistenceQuery(untypedSystem)
        .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    implicit val context: ExecutionContext = system.dispatcher
    val authenticator = ClusterSingleton(system.toTyped)
      .spawn(Authenticator(),
        "authenticator",
        Props.empty,
        ClusterSingletonSettings(system.toTyped),
        Authenticator.StopAuthenticator)
    Api(accounts, authenticator)
  }

  private def accountEntityShard(sharding: ClusterSharding,
                                 system: akka.actor.ActorSystem): ActorRef[ShardingEnvelope[AccountEntity.Command]] = {
    logger.info(s"Starting Shard for ${AccountEntity.ShardingTypeName}")
    val shardedEntity = ShardedEntity(
      AccountEntity(),
      AccountEntity.ShardingTypeName,
      AccountEntity.PassivateAccount)
    sharding.start(shardedEntity)
  }
}
