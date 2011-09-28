package com.tc.test.server.appserver.jetty7x;

import org.apache.commons.io.IOUtils;

import com.tc.lcp.CargoLinkedChildProcess;
import com.tc.lcp.HeartBeatService;
import com.tc.process.Exec;
import com.tc.process.Exec.Result;
import com.tc.test.TestConfigObject;
import com.tc.test.server.ServerParameters;
import com.tc.test.server.ServerResult;
import com.tc.test.server.appserver.AbstractAppServer;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.AppServerResult;
import com.tc.test.server.appserver.deployment.GenericServer;
import com.tc.test.server.util.AppServerUtil;
import com.tc.util.PortChooser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Jetty7xAppServer extends AbstractAppServer {
  private static final boolean NEW_INTEGRATION    = false;

  private static final String  JAVA_CMD           = System.getProperty("java.home") + File.separator + "bin"
                                                    + File.separator + "java";
  private static final String  STOP_KEY           = "secret";
  private static final String  JETTY_MAIN_CLASS   = "org.eclipse.jetty.start.Main";
  private static final long    START_STOP_TIMEOUT = 240 * 1000;

  private String               jettyConfigFile;

  private String               instanceName;
  private File                 instanceDir;
  private File                 workDir;

  private int                  jetty_port         = 0;
  private int                  stop_port          = 0;
  private Thread               runner             = null;

  public Jetty7xAppServer(final Jetty7xAppServerInstallation installation) {
    super(installation);
  }

  public ServerResult start(final ServerParameters parameters) throws Exception {
    AppServerParameters params = (AppServerParameters) parameters;
    return startJetty(params);
  }

  public void stop(final ServerParameters rawParams) throws Exception {
    final String[] cmd = new String[] { JAVA_CMD, "-DSTOP.PORT=" + stop_port, "-DSTOP.KEY=" + STOP_KEY, "-jar",
        "start.jar", "--stop" };

    System.err.println("Stopping instance " + instanceName + "...");
    Result result = Exec.execute(cmd, null, null, this.serverInstallDirectory());
    if (result.getExitCode() != 0) {
      System.err.println(result);
    }

    if (runner != null) {
      runner.join(START_STOP_TIMEOUT);
      if (runner.isAlive()) {
        System.err.println("Instance " + instanceName + " on port " + jetty_port + " still alive.");
      } else {
        System.err.println("jetty instance " + instanceName + " stopped");
      }
    }

  }

  private AppServerResult startJetty(final AppServerParameters params) throws Exception {
    prepareDeployment(params);

    // Find the jetty-terracotta module jar
    File tcModuleJar = null;
    if (NEW_INTEGRATION) {
      File tcModuleDir = new File(this.serverInstallDirectory() + File.separator + "lib" + File.separator
                                  + "terracotta");
      if (!tcModuleDir.isDirectory()) { throw new IllegalStateException(tcModuleDir + " is not a directory"); }
      String[] jars = tcModuleDir.list();
      if (jars.length != 1) { throw new IllegalStateException("wrong number of jars found in " + tcModuleDir + ": "
                                                              + Arrays.asList(jars)); }
      tcModuleJar = new File(tcModuleDir, jars[0]);
      System.err.println("Found terracotta module jar: " + tcModuleJar);
    }

    String[] jvmargs = params.jvmArgs().replaceAll("'", "").split("\\s+");
    List cmd = new ArrayList(Arrays.asList(jvmargs));
    cmd.add(0, JAVA_CMD);
    cmd.add("-cp");
    cmd.add(this.serverInstallDirectory() + File.separator + "start.jar" + File.pathSeparator
            + TestConfigObject.getInstance().extraClassPathForAppServer());
    cmd.add("-Djetty.home=" + this.serverInstallDirectory());
    cmd.add("-Djetty.port=" + jetty_port);
    cmd.add("-Djava.io.tmpdir=" + workDir.getAbsolutePath());
    cmd.add(CargoLinkedChildProcess.class.getName());
    cmd.add(JETTY_MAIN_CLASS);
    cmd.add(String.valueOf(HeartBeatService.listenPort()));
    cmd.add(instanceDir.getAbsolutePath());
    if (GenericServer.dsoEnabled() && NEW_INTEGRATION) {
      cmd.add("path=" + tcModuleJar.getAbsolutePath());
    }
    cmd.add("STOP.PORT=" + stop_port);
    cmd.add("STOP.KEY=" + STOP_KEY);
    // cmd.add("DEBUG=true");
    cmd.add("OPTIONS=All");
    cmd.add(jettyConfigFile);

    final String[] cmdArray = (String[]) cmd.toArray(new String[] {});
    final String nodeLogFile = new File(instanceDir + ".log").getAbsolutePath();

    runner = new Thread("runner for " + instanceName) {
      @Override
      public void run() {
        try {
          Result result = Exec.execute(cmdArray, nodeLogFile, null, instanceDir);
          if (result.getExitCode() != 0) {
            System.err.println(result);
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };
    runner.start();
    System.err.println("Starting jetty " + instanceName + " on port " + jetty_port + "...");
    System.err.println("Cmd: " + Arrays.asList(cmdArray));
    AppServerUtil.waitForPort(jetty_port, START_STOP_TIMEOUT);
    System.err.println("Started " + instanceName + " on port " + jetty_port);
    return new AppServerResult(jetty_port, this);
  }

  private void prepareDeployment(final AppServerParameters params) throws Exception {
    instanceName = params.instanceName();
    instanceDir = new File(sandboxDirectory(), instanceName);
    ensureDirectory(instanceDir);
    ensureDirectory(getContextsDirectory());

    File wars_dir = getWarsDirectory();
    ensureDirectory(wars_dir);

    // move wars into the correct location
    Map wars = params.deployables();
    if (wars != null && wars.size() > 0) {

      Set war_entries = wars.entrySet();
      Iterator war_entries_it = war_entries.iterator();
      while (war_entries_it.hasNext()) {
        Map.Entry war_entry = (Map.Entry) war_entries_it.next();
        File war_file = (File) war_entry.getValue();
        String context = (String) war_entry.getKey();
        writeContextFile(war_file, context);
      }
    }

    // setup deployment config
    PortChooser portChooser = new PortChooser();
    jetty_port = portChooser.chooseRandomPort();
    stop_port = portChooser.chooseRandomPort();

    workDir = new File(sandboxDirectory(), "work");
    workDir.mkdirs();

    File logsDir = new File(instanceDir, "logs");
    ensureDirectory(logsDir);

    setProperties(params, jetty_port, instanceDir);

    createConfigFile();
    if (!new File(jettyConfigFile).exists()) { throw new Exception("Jetty config file wasn't created properly"); }
  }

  private void writeContextFile(final File war, final String context) throws IOException {
    String warShortName = war.getName().toLowerCase().replace(".war", "");

    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(new File(getContextsDirectory(), warShortName + ".xml"));
      fos.write(contextFile(war.getAbsolutePath(), context).getBytes());
    } finally {
      IOUtils.closeQuietly(fos);
    }
  }

  private static void ensureDirectory(final File dir) throws Exception {
    if (!dir.exists() && dir.mkdirs() == false) { throw new Exception("Can't create directory ("
                                                                      + dir.getAbsolutePath()); }
  }

  private File getWarsDirectory() {
    return new File(instanceDir, "war");
  }

  private File getContextsDirectory() {
    return new File(instanceDir, "contexts");
  }

  private void createConfigFile() throws Exception {
    PrintWriter out = null;

    try {
      String jettyXmlContent = getJettyXml();
      jettyXmlContent = jettyXmlContent.replace("TC_CONTEXT_DIR", getContextsDirectory().getAbsolutePath());
      jettyXmlContent = jettyXmlContent.replace("TC_WORKER_NAME", instanceName);

      jettyConfigFile = new File(instanceDir, "jetty.xml").getAbsolutePath();
      System.out.println("XXX: jetty config file: " + jettyConfigFile);
      out = new PrintWriter(new FileWriter(jettyConfigFile));
      out.println(jettyXmlContent);
    } finally {
      IOUtils.closeQuietly(out);
    }
  }

  private static String contextFile(final String warFile, final String contextPath) {
    String s = "<?xml version=\"1.0\"  encoding=\"ISO-8859-1\"?>\n";
    s += "<Configure class=\"org.eclipse.jetty.webapp.WebAppContext\">\n";
    s += "  <Set name=\"contextPath\">/" + contextPath + "</Set>\n";
    s += "  <Set name=\"war\">" + warFile + "</Set>\n";
    s += "\n";
    s += "</Configure>\n";
    return s;
  }

  private String getJettyXml() throws IOException {
    InputStream in = null;
    try {
      in = Jetty7xAppServer.class.getResourceAsStream("jetty.xml");
      List<String> lines = IOUtils.readLines(in);
      StringBuilder content = new StringBuilder();
      for (String line : lines) {
        content.append(line).append("\n");
      }
      return content.toString();
    } finally {
      IOUtils.closeQuietly(in);
    }
  }
}
