/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.glassfishv3;

import com.tc.process.Exec;
import com.tc.process.Exec.Result;
import com.tc.test.server.ServerParameters;
import com.tc.test.server.ServerResult;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.AppServerResult;
import com.tc.test.server.appserver.glassfish.AbstractGlassfishAppServer;
import com.tc.test.server.appserver.glassfish.GlassfishAppServerInstallation;
import com.tc.test.server.util.AppServerUtil;
import com.tc.test.server.util.RetryException;
import com.tc.text.Banner;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GlassfishV3AppServer extends AbstractGlassfishAppServer {

  public GlassfishV3AppServer(final GlassfishAppServerInstallation installation) {
    super(installation);
  }

  @Override
  protected File getStartScript(final AppServerParameters params) {
    return new File(new File(new File(serverInstallDirectory(), "glassfish"), "bin"), getPlatformScript("startserv"));
  }

  @Override
  protected File getStopScript(final AppServerParameters params) {
    return new File(new File(new File(serverInstallDirectory(), "glassfish"), "bin"), getPlatformScript("stopserv"));
  }

  @Override
  protected ServerResult start0(final AppServerParameters params) throws Exception {
    instanceDir = createInstance(params);

    instanceDir.delete(); // createDomain will fail if directory already exists
    if (instanceDir.exists()) { throw new RuntimeException("Instance dir must not exist: "
                                                           + instanceDir.getAbsolutePath()); }

    createDomain(params);

    modifyDomainConfig(params);

    setProperties(params, httpPort, instanceDir);

    File startScript = getStartScript(params);

    final String cmd[] = new String[] { startScript.getAbsolutePath(), "--domaindir",
        instanceDir.getParentFile().getAbsolutePath(), params.instanceName() };

    final File nodeLogFile = new File(instanceDir.getParent(), instanceDir.getName() + ".log");
    final Process process = Runtime.getRuntime().exec(cmd, null, instanceDir);

    runner = new Thread("runner for " + params.instanceName()) {
      @Override
      public void run() {
        try {
          Result result = Exec.execute(process, cmd, nodeLogFile.getAbsolutePath(), null, instanceDir);
          if (result.getExitCode() != 0) {
            System.err.println(result);
          }
        } catch (Throwable e) {
          e.printStackTrace();
        }
      }
    };
    runner.start();
    System.err.println("Starting " + params.instanceName() + " on port " + httpPort + "...");

    boolean started = false;
    long timeout = System.currentTimeMillis() + AbstractGlassfishAppServer.START_STOP_TIMEOUT;
    while (System.currentTimeMillis() < timeout) {
      if (AppServerUtil.pingPort(adminPort)) {
        started = true;
        break;
      }

      if (!runner.isAlive()) {
        if (amxDebugCheck(nodeLogFile)) { throw new RetryException("NPE in AMXDebug"); }
        throw new RuntimeException("Runner thread finished before timeout");
      }
    }

    if (!started) { throw new RuntimeException("Failed to start server in "
                                               + AbstractGlassfishAppServer.START_STOP_TIMEOUT + "ms"); }

    System.err.println("Started " + params.instanceName() + " on port " + httpPort);

    waitForAppInstanceRunning(params);

    deployWars(nodeLogFile, params.deployables());

    waitForPing(nodeLogFile);

    return new AppServerResult(httpPort, this);
  }

  @Override
  public void stop(final ServerParameters rawParams) throws Exception {
    AppServerParameters params = (AppServerParameters) rawParams;
    System.err.println("Stopping instance on port " + httpPort + "...");

    File stopScript = getStopScript(params);
    final String cmd[] = new String[] { stopScript.getAbsolutePath(), "--domaindir",
        instanceDir.getParentFile().getAbsolutePath(), params.instanceName() };

    Result result = Exec.execute(cmd, null, null, stopScript.getParentFile());
    if (result.getExitCode() != 0) {
      System.err.println(result);
    }

    if (runner != null) {
      runner.join(START_STOP_TIMEOUT);
      if (runner.isAlive()) {
        Banner.errorBanner("instance still running on port " + httpPort);
      } else {
        System.err.println("Stopped instance on port " + httpPort);
      }
    }

  }

  @Override
  protected void createDomain(final AppServerParameters params) throws Exception {
    File asAdminScript = getAsadminScript();

    List cmd = new ArrayList();
    cmd.add(asAdminScript.getAbsolutePath());
    cmd.add("--interactive=false");
    cmd.add("--user");
    cmd.add(ADMIN_USER);
    cmd.add("create-domain");
    cmd.add("--adminport");
    cmd.add(String.valueOf(adminPort));
    cmd.add("--instanceport");
    cmd.add(String.valueOf(httpPort));
    cmd.add("--savemasterpassword=true");
    cmd.add("--domaindir=" + sandboxDirectory());
    cmd.add("--domainproperties");
    cmd.add("jms.port=" + pc.chooseRandomPort() + ":" + "orb.listener.port=" + pc.chooseRandomPort() + ":"
            + "http.ssl.port=" + pc.chooseRandomPort() + ":" + "orb.ssl.port=" + pc.chooseRandomPort() + ":"
            + "orb.mutualauth.port=" + pc.chooseRandomPort() + ":" + "domain.jmxPort=" + pc.chooseRandomPort());
    cmd.add("--savelogin=true");
    cmd.add("--nopassword=true");
    cmd.add(params.instanceName());

    Result result = Exec.execute((String[]) cmd.toArray(new String[] {}), null, null, asAdminScript.getParentFile());

    if (result.getExitCode() != 0) { throw new RuntimeException(result.toString()); }
  }

  @Override
  protected String[] getDisplayCommand(String script, AppServerParameters params) {
    // TODO Auto-generated method stub
    return null;
  }

}
