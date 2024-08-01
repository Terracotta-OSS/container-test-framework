/*
 * Copyright Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tc.test.server.appserver.jetty6x;

import org.apache.commons.io.IOUtils;

import com.tc.process.Exec;
import com.tc.process.Exec.Result;
import com.tc.test.server.ServerParameters;
import com.tc.test.server.ServerResult;
import com.tc.test.server.appserver.AbstractAppServer;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.AppServerResult;
import com.tc.test.server.util.AppServerUtil;
import com.tc.util.PortChooser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Jetty6xAppServer extends AbstractAppServer {
  private static final String JAVA_CMD           = System.getProperty("java.home") + File.separator + "bin"
                                                   + File.separator + "java";
  private static final String STOP_KEY           = "secret";
  private static final String JETTY_MAIN_CLASS   = "org.mortbay.start.Main";
  private static final long   START_STOP_TIMEOUT = 240 * 1000;

  private static final String webAppTarget       = "<SystemProperty name=\"jetty.home\" default=\".\"/>/webapps";
  private static final String contextsTarget     = "<SystemProperty name=\"jetty.home\" default=\".\"/>/contexts";
  private static final String eofTarget          = "</Configure>";

  private String              configFile;
  private String              instanceName;
  private File                instanceDir;
  private File                workDir;

  private int                 jetty_port         = 0;
  private int                 stop_port          = 0;
  private Thread              runner             = null;

  public Jetty6xAppServer(final Jetty6xAppServerInstallation installation) {
    super(installation);
  }

  @Override
  public ServerResult start(final ServerParameters parameters) throws Exception {
    AppServerParameters params = (AppServerParameters) parameters;
    return startJetty(params);
  }

  @Override
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

    String[] jvmargs = params.jvmArgs().replaceAll("'", "").split("\\s+");
    List cmd = new ArrayList(Arrays.asList(jvmargs));
    cmd.add(0, JAVA_CMD);
    cmd.add("-cp");
    cmd.add(this.serverInstallDirectory() + File.separator + "start.jar");
    cmd.add("-Djetty.home=" + this.serverInstallDirectory());
    cmd.add("-Djetty.port=" + jetty_port);
    cmd.add("-DSTOP.PORT=" + stop_port);
    cmd.add("-DSTOP.KEY=" + STOP_KEY);
    cmd.add("-Djava.io.tmpdir=" + workDir.getAbsolutePath());
    cmd.add(JETTY_MAIN_CLASS);
    cmd.add(configFile);

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
    String origialConfig = this.serverInstallDirectory().getAbsolutePath() + File.separator + "etc" + File.separator
                           + "jetty.xml";
    if (new File(origialConfig).exists() == false) { throw new Exception(origialConfig + " doesn't exist."); }

    StringBuffer buffer = new StringBuffer(1024);
    BufferedReader in = null;
    PrintWriter out = null;

    try {
      in = new BufferedReader(new FileReader(origialConfig));
      String line;
      while ((line = in.readLine()) != null) {
        buffer.append(line).append("\n");
      }

      int startIndex = buffer.indexOf(webAppTarget);
      if (startIndex > 0) {
        int endIndex = startIndex + webAppTarget.length();
        buffer.replace(startIndex, endIndex, getWarsDirectory().getAbsolutePath());
      } else {
        throw new RuntimeException("Can't find target: " + webAppTarget);
      }

      startIndex = buffer.indexOf(eofTarget);
      if (startIndex > 0) {
        buffer.insert(startIndex, jettyXmlAddition(instanceName));
      } else {
        throw new RuntimeException("Can't find target: " + eofTarget);
      }

      startIndex = buffer.indexOf(contextsTarget);
      if (startIndex > 0) {
        int endIndex = startIndex + contextsTarget.length();
        buffer.replace(startIndex, endIndex, getContextsDirectory().getAbsolutePath());
      } else {
        throw new RuntimeException("Can't find target: " + contextsTarget);
      }

      configFile = new File(instanceDir, "jetty.xml").getAbsolutePath();
      out = new PrintWriter(new FileWriter(configFile));
      out.println(buffer.toString());

    } finally {
      IOUtils.closeQuietly(in);
      IOUtils.closeQuietly(out);
    }
  }

  private String jettyXmlAddition(final String workerName) {
    String s = "";
    s += "  <Set name=\"sessionIdManager\">\n";
    s += "    <New id=\"idMgr\" class=\"org.mortbay.jetty.servlet.HashSessionIdManager\">\n";
    s += "      <Set name=\"workerName\">" + workerName + "</Set>\n";
    s += "    </New>\n";
    s += "  </Set>\n";
    s += "  \n";
    return s;
  }

  private static String contextFile(final String warFile, final String contextPath) {
    String s = "<?xml version=\"1.0\"  encoding=\"ISO-8859-1\"?>\n";
    s += "<Configure class=\"org.mortbay.jetty.webapp.WebAppContext\">\n";
    s += "  <Set name=\"contextPath\">/" + contextPath + "</Set>\n";
    s += "  <Set name=\"war\">" + warFile + "</Set>\n";
    s += "\n";
    s += "</Configure>\n";
    return s;
  }

}
