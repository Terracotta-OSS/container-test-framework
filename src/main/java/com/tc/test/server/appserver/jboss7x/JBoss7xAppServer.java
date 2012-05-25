/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.jboss7x;

import org.apache.commons.io.FileUtils;

import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebResponse;
import com.tc.process.Exec;
import com.tc.process.Exec.Result;
import com.tc.test.server.ServerParameters;
import com.tc.test.server.ServerResult;
import com.tc.test.server.appserver.AbstractAppServer;
import com.tc.test.server.appserver.AppServerResult;
import com.tc.test.server.appserver.StandardAppServerParameters;
import com.tc.test.server.appserver.deployment.Deployment;
import com.tc.test.server.appserver.deployment.WARBuilder;
import com.tc.test.server.util.AppServerUtil;
import com.tc.text.Banner;
import com.tc.util.PortChooser;
import com.tc.util.ReplaceLine;
import com.tc.util.runtime.Os;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JBoss7x AppServer implementation
 */
public final class JBoss7xAppServer extends AbstractAppServer {

  private static final long               START_STOP_TIMEOUT      = 240 * 1000;                       // 4 minutes
  private static final String             STARTUP_MONITOR_CONTEXT = "STARTWATCH";

  private static final String             JAVA_CMD                = System.getProperty("java.home") + File.separator
                                                                    + "bin" + File.separator + "java";

  private final File                      serverInstallDir;
  private String                          instanceName;
  private File                            instanceDir;
  private Thread                          runner                  = null;
  private final HashMap<String, PortLine> portMap                 = new HashMap<String, PortLine>();
  private int                             start_port              = 0;
  private int                             admin_port              = 0;

  public JBoss7xAppServer(JBoss7xAppServerInstallation installation) {
    super(installation);
    serverInstallDir = this.serverInstallDirectory();
  }

  public ServerResult start(ServerParameters parameters) throws Exception {
    StandardAppServerParameters params = (StandardAppServerParameters) parameters;
    instanceName = params.instanceName();
    instanceDir = createInstance(params);
    createInstanceDir();

    setJVMRoute();
    configurePorts();
    start_port = portMap.get("http").getPortNumber();
    admin_port = portMap.get("management-native").getPortNumber();

    deployWars(params.deployables());

    System.err.println("Starting jboss7 " + instanceName + " on port " + start_port + "...");

    // // create start command with standalone.sh
    // File startScript = new File(new File(serverInstallDir, "bin"), getPlatformScript("standalone"));
    // // trying to get boot log to go in the sandbox rather than the server home see
    // // https://issues.jboss.org/browse/AS7-4271
    // // might only work on linux see https://issues.jboss.org/browse/AS7-1947
    // File logDir = new File(instanceDir, "log");
    // final String startCmd[] = new String[] { startScript.getAbsolutePath(),
    // "-Dorg.jboss.boot.log.file=" + new File(logDir, "boot.log").getAbsolutePath(),
    // "-Djboss.server.log.dir=" + logDir.getAbsolutePath(),
    // "-Djboss.server.base.dir=" + instanceDir.getAbsolutePath() };

    // Try a different startup using the java command directly, to work around the boot.log init problem mentioned above
    File logDir = new File(instanceDir, "log");
    final String[] startCmd = new String[] { JAVA_CMD,
        "-Dorg.jboss.boot.log.file=" + new File(logDir, "boot.log").getAbsolutePath(),
        "-Dlogging.configuration=file:" + new File(instanceDir, "configuration/logging.properties").getAbsolutePath(),
        "-jar", new File(serverInstallDir, "jboss-modules.jar").getAbsolutePath(), "-mp",
        new File(serverInstallDir, "modules").getAbsolutePath(), "-jaxpmodule", "javax.xml.jaxp-provider",
        "org.jboss.as.standalone", "-Djboss.home.dir=" + serverInstallDir.getAbsolutePath(),
        "-Djboss.server.base.dir=" + instanceDir.getAbsolutePath() };

    System.err.println("Start cmd: " + Arrays.asList(startCmd));

    final String nodeLogFile = new File(instanceDir + ".log").getAbsolutePath();
    runner = new Thread("runner for " + instanceName) {
      @Override
      public void run() {
        try {
          Result result = Exec.execute(startCmd, nodeLogFile, null, instanceDir);
          if (result.getExitCode() != 0) {
            System.err.println(result);
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };
    runner.start();
    AppServerUtil.waitForPort(start_port, START_STOP_TIMEOUT);
    deployStartupMonitorWar();
    waitUntilWarsDeployed(START_STOP_TIMEOUT);
    System.err.println("Started " + instanceName + " on port " + start_port);
    return new AppServerResult(start_port, this);
  }

  // call jboss-cli to shutdown
  public void stop(ServerParameters parameters) throws Exception {
    // AppServerParameters params = (AppServerParameters) parameters;
    File stopScript = new File(new File(serverInstallDirectory(), "bin"), getPlatformScript("jboss-cli"));

    final String cmd[] = new String[] { stopScript.getAbsolutePath(), "--connect",
        "--controller=localhost:" + admin_port, ":shutdown" };

    System.err.println("Stop cmd: " + Arrays.asList(cmd));
    Result result = Exec.execute(cmd, null, null, stopScript.getParentFile());
    if (result.getExitCode() != 0) {
      System.err.println(result);
    }

    if (runner != null) {
      runner.join(START_STOP_TIMEOUT);
      if (runner.isAlive()) {
        Banner.errorBanner("instance still running on port " + start_port);
      } else {
        System.err.println("Stopped instance on port " + start_port);
      }
    }
  }

  /*
   * Set the jvmroute in the standalone.xml config as per <subsystem xmlns="urn:jboss:domain:web:1.1"...
   * instance-id="{jvmroute}">
   */
  private void setJVMRoute() throws IOException {
    List<ReplaceLine.Token> tokens = new ArrayList<ReplaceLine.Token>();
    tokens.add(new ReplaceLine.Token(256, "(>)", " instance-id=\"" + instanceName + "\">"));
    File dest = new File(instanceDir, "configuration/standalone.xml");
    ReplaceLine.parseFile(tokens.toArray(new ReplaceLine.Token[] {}), dest);
  }

  /*
   * (over)write ports to $instanceDir/configuration/standalone.xml Uses hard-coded line numbers to find the lines to
   * replace. All the ports we care about: management_native_port = 9999 management_http_port=9990
   * management_https_port=9443 ajp_port = 8009 http_port = 8080 https_port = 8443 osgi-http_port = 8090 remoting_port =
   * 4447 txn-recovery-environment_port 4712 txn-status-manager_port = 4713 mail-smtp_port = 25 <-- not changing this
   * one
   */
  private void configurePorts() throws FileNotFoundException, IOException {
    PortChooser portChooser = new PortChooser();
    List<ReplaceLine.Token> tokens = new ArrayList<ReplaceLine.Token>();

    PortLine p0 = new PortLine("management-native", 292, portChooser.chooseRandomPort());
    tokens.add(new ReplaceLine.Token(p0.getLineNumber(), "(port:[0-9]+)", "port:" + p0.getPortNumber()));
    portMap.put("management-native", p0);

    PortLine p1 = new PortLine("management-http", 293, portChooser.chooseRandomPort());
    tokens.add(new ReplaceLine.Token(p1.getLineNumber(), "(port:[0-9]+)", "port:" + p1.getPortNumber()));
    portMap.put("management-http", p1);

    PortLine p2 = new PortLine("management-https", 294, portChooser.chooseRandomPort());
    tokens.add(new ReplaceLine.Token(p2.getLineNumber(), "(port:[0-9]+)", "port:" + p2.getPortNumber()));
    portMap.put("management-https", p2);

    PortLine p3 = new PortLine("ajp", 295, portChooser.chooseRandomPort());
    tokens.add(new ReplaceLine.Token(p3.getLineNumber(), "(port=\"[0-9]+)", "port=\"" + p3.getPortNumber()));
    portMap.put("ajp", p3);

    PortLine p4 = new PortLine("http", 296, portChooser.chooseRandomPort());
    tokens.add(new ReplaceLine.Token(p4.getLineNumber(), "(port=\"[0-9]+)", "port=\"" + p4.getPortNumber()));
    portMap.put("http", p4);

    PortLine p5 = new PortLine("https", 297, portChooser.chooseRandomPort());
    tokens.add(new ReplaceLine.Token(p5.getLineNumber(), "(port=\"[0-9]+)", "port=\"" + p5.getPortNumber()));
    portMap.put("https", p5);

    PortLine p6 = new PortLine("osgi-http", 298, portChooser.chooseRandomPort());
    tokens.add(new ReplaceLine.Token(p6.getLineNumber(), "(port=\"[0-9]+)", "port=\"" + p6.getPortNumber()));
    portMap.put("osgi-http", p6);

    PortLine p7 = new PortLine("remoting", 299, portChooser.chooseRandomPort());
    tokens.add(new ReplaceLine.Token(p7.getLineNumber(), "(port=\"[0-9]+)", "port=\"" + p7.getPortNumber()));
    portMap.put("remoting", p7);

    PortLine p8 = new PortLine("txn-recovery-environment", 300, portChooser.chooseRandomPort());
    tokens.add(new ReplaceLine.Token(p8.getLineNumber(), "(port=\"[0-9]+)", "port=\"" + p8.getPortNumber()));
    portMap.put("txn-recovery-environment", p8);

    PortLine p9 = new PortLine("txn-status-manager", 301, portChooser.chooseRandomPort());
    tokens.add(new ReplaceLine.Token(p9.getLineNumber(), "(port=\"[0-9]+)", "port=\"" + p9.getPortNumber()));
    portMap.put("txn-status-manager", p9);

    File dest = new File(instanceDir, "configuration/standalone.xml");
    ReplaceLine.parseFile(tokens.toArray(new ReplaceLine.Token[] {}), dest);
  }

  private void createInstanceDir() throws IOException {
    // populate the instanceDir with the default standalone dir
    File defaultDir = new File(serverInstallDir, "standalone");
    FileUtils.copyDirectory(defaultDir, instanceDir);
    System.err.println("Created instance dir: " + instanceDir.getAbsolutePath());
  }

  private void deployWars(Map wars) throws IOException {
    if (wars != null && wars.size() > 0) {
      Set war_entries = wars.entrySet();
      Iterator war_entries_it = war_entries.iterator();
      while (war_entries_it.hasNext()) {
        Map.Entry war_entry = (Map.Entry) war_entries_it.next();
        File war_file = (File) war_entry.getValue();
        FileUtils.copyFileToDirectory(war_file, new File(instanceDir, "deployments"));
      }
    }
  }

  private void deployStartupMonitorWar() throws Exception {
    WARBuilder builder = new WARBuilder(STARTUP_MONITOR_CONTEXT + ".war", new File(this.sandboxDirectory(), "war"));
    builder.addServlet("ok", "/*", OkServlet.class, new HashMap(), true);
    Deployment deployment = builder.makeDeployment();
    FileUtils.copyFileToDirectory(deployment.getFileSystemPath().getFile(), new File(instanceDir, "deployments"));
  }

  private void waitUntilWarsDeployed(long waitTime) throws Exception {
    long timeToQuit = System.currentTimeMillis() + waitTime;
    WebConversation wc = new WebConversation();
    String fullURL = "http://localhost:" + start_port + "/" + STARTUP_MONITOR_CONTEXT + "/ok";
    wc.setExceptionsThrownOnErrorStatus(false);
    while (System.currentTimeMillis() < timeToQuit) {
      WebResponse response = wc.getResponse(fullURL);
      int responseCode = response.getResponseCode();
      if (responseCode == 200) {
        return;
      } else {
        Thread.sleep(500L);
      }
    }
  }

  protected static String getPlatformScript(final String name) {
    if (Os.isWindows()) { return name + ".bat"; }
    return name;
  }

  /*
   * Helps map line numbers in the <jbosshome>/standalone/configuration/standalone.xml with the associated ports
   */
  private static class PortLine {
    private final int    lineNumber;
    private final int    portNumber;
    private final String portID;

    public PortLine(String portID, int lineNumber, int portNumber) {
      this.portID = portID;
      this.lineNumber = lineNumber;
      this.portNumber = portNumber;
    }

    public String getPortID() {
      return portID;
    }

    public int getLineNumber() {
      return lineNumber;
    }

    public int getPortNumber() {
      return portNumber;
    }
  }

}
