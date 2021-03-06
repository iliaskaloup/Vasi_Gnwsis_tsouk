/*
 * Copyright 2014 Red Hat, Inc.
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *  The Eclipse Public License is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  The Apache License v2.0 is available at
 *  http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package examples;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.dns.HostnameResolverOptions;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.NetServer;

import java.util.Arrays;

/**
 * Created by tim on 08/01/15.
 */
public class CoreExamples {

  public void example1() {
    Vertx vertx = Vertx.vertx();
  }

  public void example2() {
    Vertx vertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(40));
  }

  public void example3(HttpServerRequest request) {
    request.response().putHeader("Content-Type", "text/plain").write("some text").end();
  }

  public void example4(HttpServerRequest request) {
    HttpServerResponse response = request.response();
    response.putHeader("Content-Type", "text/plain");
    response.write("some text");
    response.end();
  }

  public void example5(Vertx vertx) {
    vertx.setPeriodic(1000, id -> {
      // This handler will get called every second
      System.out.println("timer fired!");
    });
  }

  public void example6(HttpServer server) {
    // Respond to each http request with "Hello World"
    server.requestHandler(request -> {
      // This handler will be called every time an HTTP request is received at the server
      request.response().end("hello world!");
    });
  }

  public void example7(Vertx vertx) {
    vertx.executeBlocking(future -> {
      // Call some blocking API that takes a significant amount of time to return
      String result = someAPI.blockingMethod("hello");
      future.complete(result);
    }, res -> {
      System.out.println("The result is: " + res.result());
    });
  }

  public void workerExecutor1(Vertx vertx) {
    WorkerExecutor executor = vertx.createWorkerExecutor("my-worker-pool");
    executor.executeBlocking(future -> {
      // Call some blocking API that takes a significant amount of time to return
      String result = someAPI.blockingMethod("hello");
      future.complete(result);
    }, res -> {
      System.out.println("The result is: " + res.result());
    });
  }

  public void workerExecutor2(WorkerExecutor executor) {
    executor.close();
  }

  public void workerExecutor3(Vertx vertx) {
    //
    // 10 threads max
    int poolSize = 10;

    // 2 minutes
    long maxExecuteTime = 120000;

    WorkerExecutor executor = vertx.createWorkerExecutor("my-worker-pool", poolSize, maxExecuteTime);
  }

  BlockingAPI someAPI = new BlockingAPI();

  class BlockingAPI {
    String blockingMethod(String str) {
      return str;
    }
  }

  public void exampleFuture1(HttpServer httpServer, NetServer netServer) {
    Future<HttpServer> httpServerFuture = Future.future();
    httpServer.listen(httpServerFuture.completer());

    Future<NetServer> netServerFuture = Future.future();
    netServer.listen(netServerFuture.completer());

    CompositeFuture.all(httpServerFuture, netServerFuture).setHandler(ar -> {
      if (ar.succeeded()) {
        // All server started
      } else {
        // At least one server failed
      }
    });
  }

  public void exampleFuture2() {
    Future<String> future1 = Future.future();
    Future<String> future2 = Future.future();
    CompositeFuture.any(future1, future2).setHandler(ar -> {
      if (ar.succeeded()) {
        // At least one is succeeded
      } else {
        // All failed
      }
    });
  }

  public void exampleFuture3(Vertx vertx, Future<Void> startFuture) {

    FileSystem fs = vertx.fileSystem();

    Future<Void> fut1 = Future.future();

    fs.createFile("/foo", fut1.completer());
    fut1.compose(v -> {

      Future<Void> fut2 = Future.future();
      fs.writeFile("/foo", Buffer.buffer(), fut2.completer());

      // Compose fut1 with fut2
      return fut2;
    }).compose(v -> {

      // Compose fut1 with fut2 and fut3
      fs.move("/foo", "/bar", startFuture.completer());
    }, startFuture);
  }

  public void example7_1(Vertx vertx) {
    DeploymentOptions options = new DeploymentOptions().setWorker(true);
    vertx.deployVerticle("com.mycompany.MyOrderProcessorVerticle", options);
  }

  public void example8(Vertx vertx) {

    Verticle myVerticle = new MyVerticle();
    vertx.deployVerticle(myVerticle);
  }

  class MyVerticle extends AbstractVerticle {

    @Override
    public void start() throws Exception {
      super.start();
    }
  }

  public void example9(Vertx vertx) {

    // Deploy a Java verticle - the name is the fully qualified class name of the verticle class
    vertx.deployVerticle("com.mycompany.MyOrderProcessorVerticle");

    // Deploy a JavaScript verticle
    vertx.deployVerticle("verticles/myverticle.js");

    // Deploy a Ruby verticle verticle
    vertx.deployVerticle("verticles/my_verticle.rb");

  }

  public void example10(Vertx vertx) {
    vertx.deployVerticle("com.mycompany.MyOrderProcessorVerticle", res -> {
      if (res.succeeded()) {
        System.out.println("Deployment id is: " + res.result());
      } else {
        System.out.println("Deployment failed!");
      }
    });
  }

  public void example11(Vertx vertx, String deploymentID) {
    vertx.undeploy(deploymentID, res -> {
      if (res.succeeded()) {
        System.out.println("Undeployed ok");
      } else {
        System.out.println("Undeploy failed!");
      }
    });
  }

  public void example12(Vertx vertx) {
    DeploymentOptions options = new DeploymentOptions().setInstances(16);
    vertx.deployVerticle("com.mycompany.MyOrderProcessorVerticle", options);
  }


  public void example13(Vertx vertx) {
    JsonObject config = new JsonObject().put("name", "tim").put("directory", "/blah");
    DeploymentOptions options = new DeploymentOptions().setConfig(config);
    vertx.deployVerticle("com.mycompany.MyOrderProcessorVerticle", options);
  }

  public void example14(Vertx vertx) {
    DeploymentOptions options = new DeploymentOptions().setIsolationGroup("mygroup");
    options.setIsolatedClasses(Arrays.asList("com.mycompany.myverticle.*",
                       "com.mycompany.somepkg.SomeClass", "org.somelibrary.*"));
    vertx.deployVerticle("com.mycompany.myverticle.VerticleClass", options);
  }

  public void example15(Vertx vertx) {
    long timerID = vertx.setTimer(1000, id -> {
      System.out.println("And one second later this is printed");
    });

    System.out.println("First this is printed");
  }

  public void example16(Vertx vertx) {
    long timerID = vertx.setPeriodic(1000, id -> {
      System.out.println("And every second this is printed");
    });

    System.out.println("First this is printed");
  }

  public void example17(Vertx vertx, long timerID) {
    vertx.cancelTimer(timerID);
  }

  public void example18(String className, Exception exception) {

    // Note -these classes are Java only

    // You would normally maintain one static instance of Logger per Java class:

    Logger logger = LoggerFactory.getLogger(className);

    logger.info("something happened");
    logger.error("oops!", exception);
  }

  public void retrieveContext(Vertx vertx) {
    Context context = vertx.getOrCreateContext();
  }

  public void retrieveContextType(Vertx vertx) {
    Context context = vertx.getOrCreateContext();
    if (context.isEventLoopContext()) {
      System.out.println("Context attached to Event Loop");
    } else if (context.isWorkerContext()) {
      System.out.println("Context attached to Worker Thread");
    } else if (context.isMultiThreadedWorkerContext()) {
      System.out.println("Context attached to Worker Thread - multi threaded worker");
    } else if (! Context.isOnVertxThread()) {
      System.out.println("Context not attached to a thread managed by vert.x");
    }
  }

  public void runInContext(Vertx vertx) {
    vertx.getOrCreateContext().runOnContext( (v) -> {
      System.out.println("This will be executed asynchronously in the same context");
    });
  }

  public void runInContextWithData(Vertx vertx) {
    final Context context = vertx.getOrCreateContext();
    context.put("data", "hello");
    context.runOnContext((v) -> {
      String hello = context.get("data");
    });
  }

  public void systemAndEnvProperties() {
    System.getProperty("prop");
    System.getenv("HOME");
  }

  public void configureDNSServers() {
    Vertx vertx = Vertx.vertx(new VertxOptions().
        setHostnameResolverOptions(
            new HostnameResolverOptions().
                addServer("192.168.0.1").
                addServer("192.168.0.2:40000"))
    );
  }

  public void deployVerticleWithDifferentWorkerPool(Vertx vertx) {
    vertx.deployVerticle("the-verticle", new DeploymentOptions().setWorkerPoolName("the-specific-pool"));
  }

}