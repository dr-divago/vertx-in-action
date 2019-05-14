package tenksteps.userprofiles;

import io.reactivex.Completable;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.auth.mongo.MongoAuth;
import io.vertx.reactivex.ext.mongo.MongoClient;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.emptyList;

public class UserProfileApiVerticle extends AbstractVerticle {

  private static final Logger logger = LoggerFactory.getLogger(UserProfileApiVerticle.class);

  private MongoAuth authProvider;
  private MongoClient mongoClient;

  private JsonObject mongoConfig() {
    return new JsonObject()
      .put("host", "localhost")
      .put("port", 27017)
      .put("db_name", "profiles");
  }

  @Override
  public Completable rxStart() {
    mongoClient = MongoClient.createShared(vertx, mongoConfig());

    JsonObject authConfig = new JsonObject();
    authProvider = MongoAuth.create(mongoClient, authConfig);

    Router router = Router.router(vertx);
    router.post().handler(BodyHandler.create());
    router.post("/register").handler(this::register);

    return vertx.createHttpServer()
      .requestHandler(router)
      .rxListen(3000)
      .ignoreElement();
  }

  private void register(RoutingContext ctx) {
    JsonObject body = ctx.getBodyAsJson();
    if (!isValidRegistrationRequest(body)) {
      ctx.fail(400);
      return;
    }

    String login = body.getString("login");
    String password = body.getString("password");

    JsonObject extraInfo = new JsonObject()
      .put("$set", new JsonObject()
        .put("city", body.getString("city"))
        .put("deviceId", body.getString("deviceId"))
        .put("makePublic", body.getBoolean("makePublic")));

    authProvider
      .rxInsertUser(login, password, emptyList(), emptyList())
      .flatMapMaybe(docId -> {
        JsonObject query = new JsonObject().put("_id", docId);
        return mongoClient.rxFindOneAndUpdate("user", query, extraInfo);
      })
      .subscribe(
        json -> completeRegistration(ctx, json),
        err -> handleRegistrationError(ctx, err));
  }

  private boolean isValidRegistrationRequest(JsonObject json) {
    return json.containsKey("login") &&
      json.containsKey("password") &&
      json.containsKey("city") &&
      json.containsKey("deviceId") &&
      json.containsKey("makePublic");
  }

  private void completeRegistration(RoutingContext ctx, JsonObject json) {
    JsonObject response = new JsonObject().put("id", json.getString("_id"));
    ctx.response()
      .putHeader("Content-Type", "application/json")
      .end(response.encode());
  }

  private void handleRegistrationError(RoutingContext ctx, Throwable err) {
    if (err.getMessage().contains("E11000")) {
      ctx.fail(409);
    } else {
      fail500(ctx, err);
    }
  }

  private void fail500(RoutingContext ctx, Throwable err) {
    logger.error("Woops", err);
    ctx.fail(500);
  }

  public static void main(String[] args) {
    Vertx.vertx()
      .rxDeployVerticle(new UserProfileApiVerticle())
      .subscribe(
        ok -> logger.info("Server started on port 3000"),
        err -> logger.error("Woops", err));
  }
}