package lila.mod

import lila.analyse.{ Analysis, AnalysisRepo }
import lila.db.Types.Coll
import lila.db.BSON.BSONJodaDateTimeHandler
import lila.evaluation.{ AccountAction, Analysed, PlayerAssessment, PlayerAggregateAssessment, PlayerFlags, GameAssessments, Assessible }
import lila.game.Game
import lila.game.{ Game, GameRepo }
import lila.user.{ User, UserRepo }

import org.joda.time.DateTime
import reactivemongo.bson._
import scala.concurrent._

import chess.Color


final class AssessApi(
  collAssessments: Coll,
  logApi: ModlogApi,
  modApi: ModApi,
  userIdsSharingIp: String => Fu[List[String]]) {

  private implicit val playerFlagsBSONhandler = Macros.handler[PlayerFlags]
  private implicit val playerAssessmentBSONhandler = Macros.handler[PlayerAssessment]

  def createPlayerAssessment(assessed: PlayerAssessment) =
    collAssessments.update(BSONDocument("_id" -> assessed._id), assessed, upsert = true).void

  def getPlayerAssessmentById(id: String) = 
    collAssessments.find(BSONDocument("_id" -> id))
    .one[PlayerAssessment]

  def getPlayerAssessmentsByUserId(userId: String, nb: Int = 100) = 
    collAssessments.find(BSONDocument("userId" -> userId))
    .sort(BSONDocument("assessment" -> -1))
    .cursor[PlayerAssessment]
    .collect[List](nb)

  def getResultsByGameIdAndColor(gameId: String, color: Color) =
    getPlayerAssessmentById(gameId + "/" + color.name)

  def getGameResultsById(gameId: String) =
    getResultsByGameIdAndColor(gameId, Color.White) zip
    getResultsByGameIdAndColor(gameId, Color.Black) map {
      a => GameAssessments(a._1, a._2)
    }

  def getPlayerAggregateAssessment(userId: String, nb: Int = 100): Fu[Option[PlayerAggregateAssessment]] = {
    val relatedUsers = userIdsSharingIp(userId)

    getPlayerAssessmentsByUserId(userId, nb) zip
    UserRepo.byId(userId) zip
    relatedUsers zip
    (relatedUsers flatMap UserRepo.filterByEngine) map {
      case (((assessedGamesHead :: assessedGamesTail, Some(user)), relatedUs), relatedCheaters) =>
        Some(PlayerAggregateAssessment(assessedGamesHead :: assessedGamesTail, user, relatedUs, relatedCheaters))
      case _ => none
    }
  }

  def refreshAssess(gameId: String): Funit =
    GameRepo.game(gameId) zip
      AnalysisRepo.doneById(gameId) flatMap {
        case (Some(g), Some(a)) => onAnalysisReady(g, a)
        case _ => funit
      }

  def refreshAssessByUsername(username: String): Funit = withUser(username) { user =>
    GameRepo.gamesForAssessment(user.id, 200) flatMap {
      gs => (gs map {
        g => AnalysisRepo.doneById(g.id) flatMap {
          case Some(a) => onAnalysisReady(g, a)
          case _ => funit
        }
      }).sequenceFu.void
    }
  }

  def onAnalysisReady(game: Game, analysis: Analysis): Funit = {
    if (!game.isCorrespondence && game.turns >= 40 && game.mode.rated) {
      val gameAssessments: GameAssessments = Assessible(Analysed(game, analysis)).assessments
      gameAssessments.white.fold(funit){createPlayerAssessment} >>
      gameAssessments.black.fold(funit){createPlayerAssessment}
    } else funit
  }

  def assessPlayerById(userId: String): Funit = UserRepo.usernameById(userId) flatMap { 
    case Some(username) => getPlayerAggregateAssessment(username) flatMap {
      case Some(playerAggregateAssessment) => playerAggregateAssessment.action match {
        case AccountAction.EngineAndBan => modApi.autoAdjust(userId) >> logApi.engine("lichess", userId, true)// >>
          //modApi.autoBan(userId) >> logApi.ban("lichess", userId, true)
        case AccountAction.Engine => modApi.autoAdjust(userId) >> logApi.engine("lichess", userId, true)
        case AccountAction.Report => funit
        case _ => funit
      }
      case _ => funit
    }
    case _ => funit
  }
  
  private def withUser[A](username: String)(op: User => Fu[A]): Fu[A] =
    UserRepo named username flatten "[mod] missing user " + username flatMap op

}
