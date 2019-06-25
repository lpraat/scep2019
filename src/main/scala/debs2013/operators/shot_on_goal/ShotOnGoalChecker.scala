package debs2013.operators.shot_on_goal

import debs2013.Events.EnrichedEvent
import debs2013.Utils
import org.apache.flink.api.common.functions.FlatMapFunction
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor}
import org.apache.flink.api.common.typeinfo.{TypeHint, TypeInformation}
import org.apache.flink.runtime.state.{FunctionInitializationContext, FunctionSnapshotContext}
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction
import org.apache.flink.util.Collector

import scala.annotation.tailrec
import scala.collection.immutable.HashMap

class ShotOnGoalChecker extends FlatMapFunction[EnrichedEvent, String] with CheckpointedFunction {

  private var playerToTeam: HashMap[String, Int] = _
  private var playerToTeamState: ListState[HashMap[String, Int]] = _

  private var shotOnGoal: Boolean = false
  private var shotOnGoalState: ListState[Boolean] = _

  private var shootingPlayer: String = ""
  private var shootingPlayerState: ListState[String] = _

  private var lastUpdate: Long = 0
  private var lastUpdateState: ListState[Long] = _

  override def flatMap(enrichedEvent: EnrichedEvent, out: Collector[String]): Unit = {

    // TODO EXTRACT THIS
    val c: Double = (enrichedEvent.playerEvent.timestamp - 10753295594424116L)*Math.pow(10, -12) + 3.092 + 0.9888

    val ballIsInTheField = Utils.isInTheField(enrichedEvent.ballEvent.x, enrichedEvent.ballEvent.y)
    val isGameInterrupted = enrichedEvent.gameEvent.interrupted

    if (ballIsInTheField && !isGameInterrupted) {

      if (shotOnGoal) {

        if (checkShotToGoal(enrichedEvent, getInGoalAreaFunction(shootingPlayer))) {

          if (!(enrichedEvent.ballEvent.timestamp > lastUpdate)) {
            out.collect(f"${Utils.getHourMinuteSeconds(c)},${shootingPlayer},${enrichedEvent.ballEvent.x},${enrichedEvent.ballEvent.y},${enrichedEvent.ballEvent.z},${enrichedEvent.ballEvent.vel},${enrichedEvent.ballEvent.velX},${enrichedEvent.ballEvent.velY},${enrichedEvent.ballEvent.velZ}, ${enrichedEvent.ballEvent.acc},${enrichedEvent.ballEvent.accX},${enrichedEvent.ballEvent.accY},${enrichedEvent.ballEvent.accZ}")
            lastUpdate = enrichedEvent.ballEvent.timestamp
          }

        } else {
          shotOnGoal = false
          shootingPlayer = ""
        }


      } else if (!shotOnGoal && checkHit(enrichedEvent)) {

          if (checkShotToGoal(enrichedEvent, getInGoalAreaFunction(enrichedEvent.player))) {
            shotOnGoal = true
            shootingPlayer = enrichedEvent.player
            out.collect(f"${Utils.getHourMinuteSeconds(c)},${shootingPlayer},${enrichedEvent.ballEvent.x},${enrichedEvent.ballEvent.y},${enrichedEvent.ballEvent.z},${enrichedEvent.ballEvent.vel},${enrichedEvent.ballEvent.velX},${enrichedEvent.ballEvent.velY},${enrichedEvent.ballEvent.velZ}, ${enrichedEvent.ballEvent.acc},${enrichedEvent.ballEvent.accX},${enrichedEvent.ballEvent.accY},${enrichedEvent.ballEvent.accZ}")
            lastUpdate = enrichedEvent.ballEvent.timestamp
          }
      }

    } else {
      if (shotOnGoal) {
        shotOnGoal = false
        shootingPlayer = ""
      }
    }
  }

  def getInGoalAreaFunction(player: String): (Float, Float, Float) => Boolean = {
    if (playerToTeam(player) == 1) {
      Utils.inGoalAreaOfTeam1
    } else {
      Utils.inGoalAreaOfTeam2
    }
  }

  def checkHit(enrichedEvent: EnrichedEvent): Boolean = {
    enrichedEvent.ballEvent.acc > 55 &&
      Utils.distance(
        enrichedEvent.playerEvent.x, enrichedEvent.playerEvent.y, enrichedEvent.playerEvent.z,
        enrichedEvent.ballEvent.x, enrichedEvent.ballEvent.y, enrichedEvent.ballEvent.z
      ) < 1
  }

  def checkShotToGoal(enrichedEvent: EnrichedEvent, inGoalArea: (Float, Float, Float) => Boolean): Boolean = {
    val x0: Float = enrichedEvent.ballEvent.x
    val y0: Float = enrichedEvent.ballEvent.y
    val z0: Float = enrichedEvent.ballEvent.z

    val ax: Float = enrichedEvent.ballEvent.acc * enrichedEvent.ballEvent.accX
    val ay: Float = enrichedEvent.ballEvent.acc * enrichedEvent.ballEvent.accY
    val az: Float = enrichedEvent.ballEvent.acc * enrichedEvent.ballEvent.accZ - 9.8f

    val v0x: Float = enrichedEvent.ballEvent.vel * enrichedEvent.ballEvent.velX
    val v0y: Float = enrichedEvent.ballEvent.vel * enrichedEvent.ballEvent.velY
    val v0z: Float = enrichedEvent.ballEvent.vel * enrichedEvent.ballEvent.velZ

    @tailrec
    def loop(t: Float): Boolean = {
      if (t > 1.6) {
        false
      } else {
        val x_t = x0 + v0x*t
        val y_t = y0 + v0y*t
        val z_t = Math.max(z0 + v0z*t, 0)

        // TODO optimize
        if (inGoalArea(x_t ,y_t, z_t)) {
 /*          println(t)
           println(x0, y0, z0)
           println(v0x, v0y, v0z)
           println(ax, ay, az)
           println(f"X ${x_t}")
           println(f"Y ${y_t}")
           println(f"Z ${z_t}")
           println(enrichedEvent.player)
           println("---------")*/
          true
        } else {
          loop(t + 0.01f) // TODO extract parameters
        }
      }
    }

    loop(0f)
  }


  override def snapshotState(context: FunctionSnapshotContext): Unit = {
    snapshotPlayerToTeamState()
    snapshotShotOnGoalState()
    snapshotShootingPlayerState()
    snapshotLastUpdateState()
  }

  override def initializeState(context: FunctionInitializationContext): Unit = {
    initializePlayerToTeamState(context)
    initializeShotOnGoalState(context)
    initializeShootingPlayerState(context)
    initializeLastUpdateState(context)
  }

  def snapshotLastUpdateState(): Unit = {
    lastUpdateState.clear()
    lastUpdateState.add(lastUpdate)
  }

  def snapshotPlayerToTeamState(): Unit = {
    playerToTeamState.clear()
    playerToTeamState.add(playerToTeam)
  }

  def snapshotShotOnGoalState(): Unit = {
    shotOnGoalState.clear()
    shotOnGoalState.add(shotOnGoal)
  }

  def snapshotShootingPlayerState(): Unit = {
    shootingPlayerState.clear()
    shootingPlayerState.add(shootingPlayer)
  }

  def initializePlayerToTeamState(context: FunctionInitializationContext): Unit = {
    val descriptor = new ListStateDescriptor[HashMap[String, Int]](
      "playerToTeam",
      TypeInformation.of(new TypeHint[HashMap[String, Int]]() {})
    )

    playerToTeamState = context.getOperatorStateStore.getListState(descriptor)

    if (context.isRestored) {
      playerToTeam = playerToTeamState.get().iterator().next()
    } else {
      playerToTeam = HashMap(
        "Nick Gertje" -> 1,
        "Dennis Dotterweich" -> 1,
        "Niklas Waelzlein" -> 1,
        "Wili Sommer" -> 1,
        "Philipp Harlass" -> 1,
        "Roman Hartleb" -> 1,
        "Erik Engelhardt" -> 1,
        "Sandro Schneider" -> 1,

        "Leon Krapf" -> 2,
        "Kevin Baer" -> 2,
        "Luca Ziegler" -> 2,
        "Ben Mueller" -> 2,
        "Vale Reitstetter" -> 2,
        "Christopher Lee" -> 2,
        "Leon Heinze" -> 2,
        "Leo Langhans" -> 2
      )
    }
  }

  def initializeShotOnGoalState(context: FunctionInitializationContext): Unit = {
    val descriptor = new ListStateDescriptor[Boolean](
      "shotOnGoal",
      TypeInformation.of(new TypeHint[Boolean]() {})
    )

    shotOnGoalState = context.getOperatorStateStore.getListState(descriptor)

    if (context.isRestored) {
      shotOnGoal = shotOnGoalState.get().iterator().next()
    }
  }

  def initializeShootingPlayerState(context: FunctionInitializationContext): Unit = {
    val descriptor = new ListStateDescriptor[Boolean](
      "shootingPlayer",
      TypeInformation.of(new TypeHint[Boolean]() {})
    )

    shotOnGoalState = context.getOperatorStateStore.getListState(descriptor)

    if (context.isRestored) {
      shotOnGoal = shotOnGoalState.get().iterator().next()
    }
  }


  def initializeLastUpdateState(context: FunctionInitializationContext): Unit = {
    val descriptor = new ListStateDescriptor[Long](
      "lastUpdate",
      TypeInformation.of(new TypeHint[Long]() {})
    )

    lastUpdateState = context.getOperatorStateStore.getListState(descriptor)

    if (context.isRestored) {
      lastUpdate = lastUpdateState.get().iterator().next()
    }
  }




}
