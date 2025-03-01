package controllers

import chess.Color
import play.api.data.Form
import play.api.libs.json.*
import play.api.mvc.*
import scala.util.chaining.*
import views.*

import lila.app.{ given, * }
import lila.common.ApiVersion
import lila.common.Json.given
import lila.common.config.MaxPerSecond
import lila.puzzle.PuzzleForm.RoundData
import lila.puzzle.{ Puzzle as Puz, PuzzleAngle, PuzzleSettings, PuzzleStreak, PuzzleTheme, PuzzleDifficulty }
import lila.user.{ Me, User }
import lila.common.LangPath
import play.api.i18n.Lang

final class Puzzle(env: Env, apiC: => Api) extends LilaController(env):

  private val cookieDifficulty = "puz-diff"

  private def renderJson(
      puzzle: Puz,
      angle: PuzzleAngle,
      replay: Option[lila.puzzle.PuzzleReplay] = None,
      newMe: Option[Me] = None,
      apiVersion: Option[ApiVersion] = None
  )(using ctx: AnyContext): Fu[JsObject] =
    given Option[Me] = newMe orElse ctx.me
    if apiVersion.exists(v => !ApiVersion.puzzleV2(v))
    then env.puzzle.jsonView.bc(puzzle)
    else env.puzzle.jsonView(puzzle, angle.some, replay)

  private def renderShow(
      puzzle: Puz,
      angle: PuzzleAngle,
      color: Option[Color] = None,
      replay: Option[lila.puzzle.PuzzleReplay] = None,
      langPath: Option[LangPath] = None
  )(using ctx: WebContext) =
    renderJson(puzzle, angle, replay) zip
      ctx.me.so(u => env.puzzle.session.getSettings(u) dmap some) map { (json, settings) =>
        Ok(
          views.html.puzzle
            .show(
              puzzle,
              json,
              env.puzzle.jsonView.pref(ctx.pref),
              settings | PuzzleSettings.default(color),
              langPath
            )
        ).enableSharedArrayBuffer
      }

  def daily = Open:
    NoBot:
      OptionFuResult(env.puzzle.daily.get): daily =>
        negotiate(
          html = renderShow(daily.puzzle, PuzzleAngle.mix),
          api = v => renderJson(daily.puzzle, PuzzleAngle.mix, apiVersion = v.some) dmap { Ok(_) }
        ) dmap (_.noCache)

  def apiDaily = Anon:
    env.puzzle.daily.get.flatMap:
      _.fold(notFoundJson()): daily =>
        JsonOk(env.puzzle.jsonView(daily.puzzle, none, none))

  def apiShow(id: PuzzleId) = Anon:
    env.puzzle.api.puzzle find id flatMap {
      _.fold(notFoundJson()): puzzle =>
        JsonOk(env.puzzle.jsonView(puzzle, none, none))
    }

  def home = Open(serveHome)

  def homeLang = LangPage(routes.Puzzle.home.url)(serveHome)

  private def serveHome(using WebContext) = NoBot:
    val angle = PuzzleAngle.mix
    nextPuzzleForMe(angle, none) flatMap {
      _.fold(redirectNoPuzzle):
        renderShow(_, angle, langPath = LangPath(routes.Puzzle.home).some)
    }

  private def nextPuzzleForMe(
      angle: PuzzleAngle,
      color: Option[Option[Color]],
      difficulty: PuzzleDifficulty = PuzzleDifficulty.Normal
  )(using ctx: WebContext): Fu[Option[Puz]] =
    ctx.me match
      case Some(me) =>
        ctx.req.session.get(cookieDifficulty).flatMap(PuzzleDifficulty.find).so {
          env.puzzle.session.setDifficulty(me, _)
        } >>
          color.so { env.puzzle.session.setAngleAndColor(me, angle, _) } >>
          env.puzzle.selector.nextPuzzleFor(me, angle)
      case None => env.puzzle.anon.getOneFor(angle, difficulty, ~color)

  private def redirectNoPuzzle: Fu[Result] =
    Redirect(routes.Puzzle.themes).flashFailure("No more puzzles available! Try another theme.")

  def complete(angleStr: String, id: PuzzleId) = OpenBody:
    NoBot:
      Puz.toId(id) so { pid =>
        onComplete(env.puzzle.forms.round)(pid, PuzzleAngle findOrMix angleStr, mobileBc = false)
      }

  def mobileBcRound(nid: Long) = OpenBody:
    Puz.numericalId(nid) so {
      onComplete(env.puzzle.forms.bc.round)(_, PuzzleAngle.mix, mobileBc = true)
    }

  def ofPlayer(name: Option[UserStr], page: Int) = Open:
    val userId = name flatMap lila.user.User.validateId
    userId.so(env.user.repo.enabledById) orElse fuccess(ctx.me.map(_.user)) flatMap { user =>
      user.so { env.puzzle.api.puzzle.of(_, page) dmap some } map { puzzles =>
        Ok(views.html.puzzle.ofPlayer(name.so(_.value), user, puzzles))
      }
    }

  private def onComplete[A](form: Form[RoundData])(id: PuzzleId, angle: PuzzleAngle, mobileBc: Boolean)(using
      ctx: WebBodyContext[A]
  ) =
    form
      .bindFromRequest()
      .fold(
        jsonFormError,
        data =>
          data.streakPuzzleId.match {
            case Some(streakNextId) =>
              env.puzzle.api.puzzle.find(streakNextId) flatMap {
                case None => fuccess(Json.obj("streakComplete" -> true))
                case Some(puzzle) =>
                  for
                    score <- data.streakScore
                    if data.win.no
                    if score > 0
                    _ = lila.mon.streak.run.score(ctx.isAuth.toString).record(score)
                    userId <- ctx.userId
                  do setStreakResult(userId, score)
                  renderJson(puzzle, angle) map { nextJson =>
                    Json.obj("next" -> nextJson)
                  }
              }
            case None =>
              lila.mon.puzzle.round.attempt(ctx.isAuth, angle.key, data.rated).increment()
              ctx.me match
                case Some(me) =>
                  env.puzzle.finisher(id, angle, me, data.win, data.mode) flatMapz { (round, perf) =>
                    val newMe = me.map(_.copy(perfs = me.perfs.copy(puzzle = perf)))
                    for
                      _ <- env.puzzle.session.onComplete(round, angle)
                      json <-
                        if mobileBc then
                          fuccess:
                            env.puzzle.jsonView.bc.userJson(perf.intRating) ++ Json.obj(
                              "round" -> Json.obj(
                                "ratingDiff" -> 0,
                                "win"        -> data.win
                              ),
                              "voted" -> round.vote
                            )
                        else
                          (data.replayDays, angle.asTheme) match {
                            case (Some(replayDays), Some(theme)) =>
                              for
                                _    <- env.puzzle.replay.onComplete(round, replayDays, angle)
                                next <- env.puzzle.replay(me, replayDays.some, theme)
                                json <- next match
                                  case None => fuccess(Json.obj("replayComplete" -> true))
                                  case Some((puzzle, replay)) =>
                                    renderJson(puzzle, angle, replay.some) map { nextJson =>
                                      Json.obj(
                                        "round" -> env.puzzle.jsonView.roundJson.web(round, perf)(using me),
                                        "next"  -> nextJson
                                      )
                                    }
                              yield json
                            case _ =>
                              for
                                next     <- nextPuzzleForMe(angle, none)
                                nextJson <- next.so { renderJson(_, angle, none, newMe.some) dmap some }
                              yield Json.obj(
                                "round" -> env.puzzle.jsonView.roundJson.web(round, perf)(using me),
                                "next"  -> nextJson
                              )
                          }
                    yield json
                  }
                case None =>
                  env.puzzle.finisher.incPuzzlePlays(id)
                  if (mobileBc) fuccess(Json.obj("user" -> false))
                  else
                    nextPuzzleForMe(angle, data.color map some)
                      .flatMap:
                        _ so { renderJson(_, angle) dmap some }
                      .map: json =>
                        Json.obj("next" -> json)
          } dmap JsonOk
      )

  def streak     = Open(serveStreak)
  def streakLang = LangPage(routes.Puzzle.streak)(serveStreak)

  private def serveStreak(using ctx: WebContext) = NoBot:
    streakJsonAndPuzzle.mapz: (json, puzzle) =>
      Ok(
        views.html.puzzle
          .show(
            puzzle,
            json,
            env.puzzle.jsonView.pref(ctx.pref),
            PuzzleSettings.default,
            langPath = LangPath(routes.Puzzle.streak).some
          )
      ).noCache.enableSharedArrayBuffer

  private def streakJsonAndPuzzle(using Lang) =
    given Option[Me] = none
    env.puzzle.streak.apply.flatMapz { case PuzzleStreak(ids, puzzle) =>
      env.puzzle.jsonView(puzzle = puzzle, PuzzleAngle.mix.some, none) map { puzzleJson =>
        (puzzleJson ++ Json.obj("streak" -> ids), puzzle).some
      }
    }

  private def setStreakResult(userId: UserId, score: Int) =
    lila.common.Bus.publish(lila.hub.actorApi.puzzle.StreakRun(userId, score), "streakRun")
    env.user.repo.addStreakRun(userId, score)

  def apiStreak = Anon:
    streakJsonAndPuzzle.mapz: (json, _) =>
      JsonOk(json)

  def apiStreakResult(score: Int) = ScopedBody(_.Puzzle.Write, _.Web.Mobile) { _ ?=> me ?=>
    if score > 0 && score < lila.puzzle.PuzzleForm.maxStreakScore then
      lila.mon.streak.run.score("mobile").record(score)
      setStreakResult(me, score)
      NoContent
    else BadRequest
  }

  def vote(id: PuzzleId) = AuthBody { _ ?=> me ?=>
    NoBot:
      env.puzzle.forms.vote
        .bindFromRequest()
        .fold(
          jsonFormError,
          vote => env.puzzle.api.vote.update(id, me, vote) inject jsonOkResult
        )
  }

  def voteTheme(id: PuzzleId, themeStr: String) = AuthBody { _ ?=> me ?=>
    NoBot:
      PuzzleTheme.findDynamic(themeStr) so { theme =>
        env.puzzle.forms.themeVote
          .bindFromRequest()
          .fold(
            jsonFormError,
            vote => env.puzzle.api.theme.vote(me, id, theme.key, vote) inject jsonOkResult
          )
      }
  }

  def setDifficulty(theme: String) = AuthBody { _ ?=> me ?=>
    NoBot:
      env.puzzle.forms.difficulty
        .bindFromRequest()
        .fold(
          jsonFormError,
          diff =>
            PuzzleDifficulty.find(diff) so { env.puzzle.session.setDifficulty(me, _) } inject
              Redirect(routes.Puzzle.show(theme))
                .withCookies(env.lilaCookie.session(cookieDifficulty, diff))
        )
  }

  def themes     = Open(serveThemes)
  def themesLang = LangPage(routes.Puzzle.themes)(serveThemes)

  private def serveThemes(using WebContext) =
    env.puzzle.api.angles.map: all =>
      Ok(views.html.puzzle.theme.list(all))

  def openings(order: String) = Open:
    env.puzzle.opening.collection flatMap { collection =>
      ctx.me
        .so: me =>
          env.insight.api.insightUser(me) map {
            _.some.filterNot(_.isEmpty) so { insightUser =>
              collection.makeMine(insightUser.families, insightUser.openings).some
            }
          }
        .map: mine =>
          Ok(views.html.puzzle.opening.all(collection, mine, lila.puzzle.PuzzleOpening.Order(order)))
    }

  def show(angleOrId: String) = Open(serveShow(angleOrId))
  def showLang(lang: String, angleOrId: String) =
    LangPage(routes.Puzzle.show(angleOrId).url)(serveShow(angleOrId))(lang)

  private def serveShow(angleOrId: String)(using ctx: WebContext) = NoBot:
    val langPath = LangPath(routes.Puzzle.show(angleOrId)).some
    PuzzleAngle find angleOrId match
      case Some(angle) =>
        nextPuzzleForMe(angle, none) flatMap {
          _.fold(redirectNoPuzzle) { renderShow(_, angle, langPath = langPath) }
        }
      case _ =>
        lila.puzzle.Puzzle toId angleOrId match
          case Some(id) =>
            OptionFuResult(env.puzzle.api.puzzle find id): puzzle =>
              ctx.me.so { env.puzzle.api.casual.setCasualIfNotYetPlayed(_, puzzle) } >>
                renderShow(puzzle, PuzzleAngle.mix, langPath = langPath)
          case _ =>
            angleOrId.toLongOption
              .flatMap(Puz.numericalId.apply)
              .so(env.puzzle.api.puzzle.find) map {
              case None      => Redirect(routes.Puzzle.home)
              case Some(puz) => Redirect(routes.Puzzle.show(puz.id))
            }

  def showWithAngle(angleKey: String, id: PuzzleId) = Open:
    NoBot:
      val angle = PuzzleAngle.findOrMix(angleKey)
      OptionFuResult(env.puzzle.api.puzzle find id): puzzle =>
        if angle.asTheme.exists(theme => !puzzle.themes.contains(theme))
        then Redirect(routes.Puzzle.show(puzzle.id))
        else
          ctx.me.so { env.puzzle.api.casual.setCasualIfNotYetPlayed(_, puzzle) } >>
            renderShow(puzzle, angle)

  def angleAndColor(angleKey: String, colorKey: String) = Open:
    NoBot:
      PuzzleAngle
        .find(angleKey)
        .fold(Redirect(routes.Puzzle.openings()).toFuccess): angle =>
          val color = Color fromName colorKey
          nextPuzzleForMe(angle, color.some) flatMap {
            _.fold(redirectNoPuzzle) { renderShow(_, angle, color = color) }
          }

  def frame = Anon:
    env.puzzle.daily.get.map:
      _.fold(NotFound)(html.puzzle.embed(_))

  def activity = Scoped(_.Puzzle.Read, _.Web.Mobile) { ctx ?=> me ?=>
    val config = lila.puzzle.PuzzleActivity.Config(
      user = me,
      max = getInt("max").map(_ atLeast 1),
      before = getTimestamp("before")
    )
    apiC.GlobalConcurrencyLimitPerIpAndUserOption(me.some)(env.puzzle.activity.stream(config)): source =>
      Ok.chunked(source).as(ndJsonContentType) pipe noProxyBuffer
  }

  def apiDashboard(days: Int) = AuthOrScoped(_.Puzzle.Read, _.Web.Mobile) { _ ?=> me ?=>
    JsonOptionOk:
      env.puzzle.dashboard(me, days) map2 { env.puzzle.jsonView.dashboardJson(_, days) }
  }

  def dashboard(days: Int, path: String = "home", u: Option[UserStr]) =
    DashboardPage(u) { ctx ?=> user =>
      env.puzzle.dashboard(user, days).map { dashboard =>
        path match
          case "dashboard" => Ok(views.html.puzzle.dashboard.home(user, dashboard, days))
          case "improvementAreas" =>
            Ok(views.html.puzzle.dashboard.improvementAreas(user, dashboard, days))
          case "strengths" => Ok(views.html.puzzle.dashboard.strengths(user, dashboard, days))
          case _ => Redirect(routes.Puzzle.dashboard(days, "dashboard", !ctx.is(user) option user.username))
      }
    }

  def replay(days: Int, themeKey: PuzzleTheme.Key) = Auth { ctx ?=> me ?=>
    val theme         = PuzzleTheme.findOrMix(themeKey)
    val checkedDayOpt = lila.puzzle.PuzzleDashboard.getClosestDay(days)
    env.puzzle.replay(me, checkedDayOpt, theme.key) flatMap {
      case None                   => Redirect(routes.Puzzle.dashboard(days, "home", none))
      case Some((puzzle, replay)) => renderShow(puzzle, PuzzleAngle(theme), replay = replay.some)
    }
  }

  def history(page: Int, u: Option[UserStr]) = DashboardPage(u) { _ ?=> user =>
    Reasonable(page):
      env.puzzle
        .history(user, page)
        .map: history =>
          Ok(views.html.puzzle.history(user, history))
  }

  def apiBatchSelect(angleStr: String) = AnonOrScoped(_.Puzzle.Read, _.Web.Mobile): ctx ?=>
    batchSelect(PuzzleAngle findOrMix angleStr, reqDifficulty, getInt("nb") | 15).dmap(Ok.apply)

  private def reqDifficulty(using req: RequestHeader) = PuzzleDifficulty.orDefault(~get("difficulty"))
  private def batchSelect(angle: PuzzleAngle, difficulty: PuzzleDifficulty, nb: Int)(using me: Option[Me]) =
    env.puzzle.batch.nextForMe(angle, difficulty, nb atMost 50) flatMap
      env.puzzle.jsonView.batch

  def apiBatchSolve(angleStr: String) = AnonOrScopedBody(parse.json)(_.Puzzle.Write, _.Web.Mobile): ctx ?=>
    ctx.body.body
      .validate[lila.puzzle.PuzzleForm.batch.SolveData]
      .fold(
        err => BadRequest(err.toString),
        data =>
          val angle = PuzzleAngle findOrMix angleStr
          for
            rounds <- ctx.me match
              case Some(me) =>
                env.puzzle.finisher.batch(me, angle, data.solutions).map {
                  _.map { (round, rDiff) => env.puzzle.jsonView.roundJson.api(round, rDiff) }
                }
              case None =>
                data.solutions.map { sol => env.puzzle.finisher.incPuzzlePlays(sol.id) }.parallel inject Nil
            given Option[Me] <- ctx.me.so(env.user.repo.me)
            nextPuzzles      <- batchSelect(angle, reqDifficulty, ~getInt("nb"))
            result = nextPuzzles ++ Json.obj("rounds" -> rounds)
          yield Ok(result)
      )

  def mobileBcLoad(nid: Long) = Open:
    negotiate(
      html = notFound,
      _ =>
        OptionFuOk(Puz.numericalId(nid) so env.puzzle.api.puzzle.find): puz =>
          env.puzzle.jsonView.bc(puz)
        .dmap(_ as JSON)
    )

  // XHR load next play puzzle
  def mobileBcNew = Open:
    NoBot:
      negotiate(
        html = notFound,
        api = v => {
          val angle = PuzzleAngle.mix
          nextPuzzleForMe(angle, none) flatMap {
            _.fold(notFoundJson()) { p => JsonOk(renderJson(p, angle, apiVersion = v.some)) }
          }
        }
      )

  /* Mobile API: select a bunch of puzzles for offline use */
  def mobileBcBatchSelect = Auth { ctx ?=> _ ?=>
    negotiate(
      html = notFound,
      api = _ =>
        val nb = getInt("nb") getOrElse 15 atLeast 1 atMost 30
        env.puzzle.batch.nextForMe(PuzzleDifficulty.default, nb) flatMap { puzzles =>
          env.puzzle.jsonView.bc.batch(puzzles)
        } dmap { Ok(_) }
    )
  }

  /* Mobile API: tell the server about puzzles solved while offline */
  def mobileBcBatchSolve = AuthBody(parse.json) { ctx ?=> me ?=>
    negotiate(
      html = notFound,
      api = _ => {
        import lila.puzzle.PuzzleForm.bc.*
        import lila.puzzle.PuzzleWin
        ctx.body.body
          .validate[SolveDataBc]
          .fold(
            err => BadRequest(err.toString),
            data =>
              data.solutions.lastOption
                .flatMap: solution =>
                  Puz
                    .numericalId(solution.id)
                    .map(_ -> PuzzleWin(solution.win))
                .so: (id, solution) =>
                  env.puzzle.finisher(id, PuzzleAngle.mix, me, solution, chess.Mode.Rated)
                .map:
                  case None => Ok(env.puzzle.jsonView.bc.userJson(me.perfs.puzzle.intRating))
                  case Some((round, perf)) =>
                    env.puzzle.session.onComplete(round, PuzzleAngle.mix)
                    Ok(env.puzzle.jsonView.bc.userJson(perf.intRating))
          )
      }
    )
  }

  def mobileBcVote(nid: Long) = AuthBody { ctx ?=> me ?=>
    negotiate(
      html = notFound,
      api = _ =>
        env.puzzle.forms.bc.vote
          .bindFromRequest()
          .fold(
            jsonFormError,
            intVote =>
              Puz.numericalId(nid) so {
                env.puzzle.api.vote.update(_, me, intVote == 1) inject jsonOkResult
              }
          )
    )
  }

  def help = Open:
    html.site.helpModal.puzzle

  private def DashboardPage(username: Option[UserStr])(f: WebContext ?=> User => Fu[Result]) =
    Auth { ctx ?=> me ?=>
      username
        .so(env.user.repo.byId)
        .flatMapz: user =>
          (fuccess(isGranted(_.CheatHunter)) >>|
            (user.enabled.yes so env.clas.api.clas.isTeacherOf(me, user.id))) map {
            _ option user
          }
        .dmap(_ | me.user)
        .flatMap(f(_))
    }
