/*
 * Copyright 2003-2008 Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
 */
package com.tc.test.server.appserver.glassfish;

import org.apache.commons.io.FileUtils;
import org.apache.xalan.xsltc.trax.TransformerFactoryImpl;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.tc.process.Exec;
import com.tc.process.Exec.Result;
import com.tc.test.server.ServerParameters;
import com.tc.test.server.ServerResult;
import com.tc.test.server.appserver.AbstractAppServer;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.AppServerResult;
import com.tc.test.server.appserver.User;
import com.tc.test.server.appserver.deployment.DeploymentBuilder;
import com.tc.test.server.appserver.deployment.WARBuilder;
import com.tc.test.server.util.AppServerUtil;
import com.tc.test.server.util.ParamsWithRetry;
import com.tc.test.server.util.RetryException;
import com.tc.text.Banner;
import com.tc.util.Assert;
import com.tc.util.Grep;
import com.tc.util.PortChooser;
import com.tc.util.concurrent.ThreadUtil;
import com.tc.util.runtime.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Glassfish AppServer implementation
 */
public abstract class AbstractGlassfishAppServer extends AbstractAppServer {

  static {
    clearAsAdminFiles();
  }

  public static final int     STARTUP_RETRIES    = 3;

  public static final String  JAVA_CMD           = System.getProperty("java.home") + File.separator + "bin"
                                                   + File.separator + "java";

  public static final String  ADMIN_USER         = "admin";
  public static final String  PASSWD             = "password";
  public static final String  PINGWAR            = "ping";

  public static final long    START_STOP_TIMEOUT = 1000 * 300;
  protected final PortChooser pc                 = new PortChooser();
  protected Thread            runner;
  protected File              instanceDir;

  protected int               httpPort;
  protected int               adminPort;

  private Process             process;

  public AbstractGlassfishAppServer(final GlassfishAppServerInstallation installation) {
    super(installation);
  }

  protected File getPasswdFile(User user) throws IOException {
    File passwdFile = new File(instanceDir.getParentFile(), "passwd" + System.currentTimeMillis() + ".txt");

    PrintWriter out = null;
    try {
      out = new PrintWriter(new FileOutputStream(passwdFile));
      out.println("AS_ADMIN_PASSWORD=" + PASSWD);
      out.println("AS_ADMIN_ADMINPASSWORD=" + PASSWD);
      out.println("AS_ADMIN_MASTERPASSWORD=" + PASSWD);
      if (user != null) {
        out.println("AS_ADMIN_USERPASSWORD=" + user.getPassword());
      }
    } finally {
      if (out != null) {
        out.close();
      }
    }

    return passwdFile;
  }

  protected File getPasswdFile() throws IOException {
    return getPasswdFile(null);
  }

  protected static String getPlatformScript(final String name) {
    if (Os.isWindows()) { return name + ".bat"; }
    return name;
  }

  protected void createDomain(final AppServerParameters params) throws Exception {
    File asAdminScript = getAsadminScript();

    List cmd = new ArrayList();
    cmd.add(asAdminScript.getAbsolutePath());
    cmd.add("create-domain");
    cmd.add("--interactive=false");
    cmd.add("--domaindir=" + sandboxDirectory());
    cmd.add("--adminport");
    cmd.add(String.valueOf(adminPort));
    cmd.add("--adminuser");
    cmd.add(ADMIN_USER);
    cmd.add("--passwordfile");
    cmd.add(getPasswdFile().getAbsolutePath());
    cmd.add("--instanceport");
    cmd.add(String.valueOf(httpPort));
    cmd.add("--savemasterpassword=true");
    cmd.add("--domainproperties");
    cmd.add("jms.port=" + pc.chooseRandomPort() + ":" + "orb.listener.port=" + pc.chooseRandomPort() + ":"
            + "http.ssl.port=" + pc.chooseRandomPort() + ":" + "orb.ssl.port=" + pc.chooseRandomPort() + ":"
            + "orb.mutualauth.port=" + pc.chooseRandomPort() + ":" + "domain.jmxPort=" + pc.chooseRandomPort());
    cmd.add("--savelogin=true");
    cmd.add(params.instanceName());

    Result result = Exec.execute((String[]) cmd.toArray(new String[] {}), null, null, asAdminScript.getParentFile());

    if (result.getExitCode() != 0) { throw new RuntimeException(result.toString()); }
  }

  protected void createUsers(AppServerParameters params) throws Exception {
    for (User user : params.getUsers()) {
      File asAdminScript = getAsadminScript();

      List cmd = new ArrayList();
      cmd.add(asAdminScript.getAbsolutePath());
      cmd.add("create-file-user");
      cmd.add("--interactive=false");
      cmd.add("--port=" + adminPort);
      cmd.add("--user=" + ADMIN_USER);
      cmd.add("--passwordfile");
      cmd.add(getPasswdFile(user).getAbsolutePath());
      cmd.add(user.getName());

      Result result = Exec.execute((String[]) cmd.toArray(new String[] {}), null, null, asAdminScript.getParentFile());

      if (result.getExitCode() != 0) { throw new RuntimeException(result.toString()); }
    }

  }

  protected static void checkFile(final File file) {
    if (!file.isFile()) { throw new RuntimeException(file.getAbsolutePath() + " is not a file or does not exist"); }
    if (!file.canRead()) { throw new RuntimeException(file.getAbsolutePath() + " cannot be read"); }
  }

  protected File getInstanceFile(final String path) {
    Assert.assertNotNull(instanceDir);
    File f = new File(instanceDir, path);
    checkFile(f);
    return f;
  }

  protected File getAsadminScript() {
    File glassfishInstall = this.serverInstallDirectory();
    File asAdminScript = new File(new File(glassfishInstall, "bin"), getPlatformScript("asadmin"));
    checkFile(asAdminScript);
    return asAdminScript;
  }

  @Override
  public ServerResult start(final ServerParameters rawParams) throws Exception {
    AppServerParameters params = (AppServerParameters) rawParams;
    for (int i = 0; i < STARTUP_RETRIES; i++) {

      // choose/rechoose these ports for each retry
      adminPort = pc.chooseRandomPort();
      httpPort = pc.chooseRandomPort();

      try {
        return start0(new ParamsWithRetry(params, i));
      } catch (RetryException re) {
        Banner.warnBanner("Re-trying server startup (" + i + ") " + re.getMessage());

        if (process != null) {
          try {
            process.destroy();
          } catch (Throwable t) {
            t.printStackTrace();
          }
        }

        continue;
      }
    }

    throw new RuntimeException("Failed to start server in " + STARTUP_RETRIES + " attempts");
  }

  protected ServerResult start0(final AppServerParameters params) throws Exception {
    instanceDir = createInstance(params);

    instanceDir.delete(); // createDomain will fail if directory already exists
    if (instanceDir.exists()) { throw new RuntimeException("Instance dir must not exist: "
                                                           + instanceDir.getAbsolutePath()); }

    createDomain(params);

    modifyDomainConfig(params);

    setProperties(params, httpPort, instanceDir);

    final String cmd[] = getStartupCommand(params);
    final File nodeLogFile = new File(instanceDir.getParent(), instanceDir.getName() + ".log");
    process = Runtime.getRuntime().exec(cmd, null, instanceDir);

    runner = new Thread("runner for " + params.instanceName()) {
      @Override
      public void run() {
        try {
          Result result = Exec.execute(process, cmd, nodeLogFile.getAbsolutePath(), startupInput(), instanceDir);
          if (result.getExitCode() != 0) {
            System.out.println(result);
          }
        } catch (Throwable e) {
          e.printStackTrace();
        }
      }
    };
    runner.start();
    System.out.println("Starting " + params.instanceName() + " on port " + httpPort + "...");

    boolean started = false;
    long timeout = System.currentTimeMillis() + START_STOP_TIMEOUT;
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

    if (!started) { throw new RuntimeException("Failed to start server in " + START_STOP_TIMEOUT + "ms"); }

    System.out.println("Started " + params.instanceName() + " on port " + httpPort);

    waitForAppInstanceRunning(params);

    createUsers(params);

    deployWars(nodeLogFile, params.deployables());

    waitForPing(nodeLogFile);

    return new AppServerResult(httpPort, this);
  }

  private static void clearAsAdminFiles() {
    // These files seem to grow over time and can get corrupted -- so clear them each run

    String userHome = System.getProperty("user.home");
    if (userHome != null) {
      File home = new File(userHome);
      if (home.isDirectory()) {
        for (File file : home.listFiles()) {
          if (file.getName().equals(".asadminpass") || file.getName().equals(".asadmintruststore")) {
            // Banner.infoBanner("Deleting " + file.getAbsolutePath());
            if (!file.delete()) { throw new AssertionError("cannot delete: " + file.getAbsolutePath()); }
          }
        }
      } else {
        throw new AssertionError(home.getAbsolutePath() + " is not a directory?");
      }
    }
  }

  protected static boolean amxDebugCheck(final File nodeLogFile) throws IOException {
    // see DEV-1722
    List<CharSequence> hits = Grep.grep("^Caused by: java.lang.NullPointerException$", nodeLogFile);
    List<CharSequence> hits2 = Grep.grep("^\tat com.sun.appserv.management.base.AMXDebug.getDebug", nodeLogFile);

    return (!hits.isEmpty() && !hits2.isEmpty());
  }

  protected void waitForPing(File nodeLogFile) throws Exception {
    String pingUrl = "http://localhost:" + httpPort + "/ping/index.html";
    WebClient wc = new WebClient();
    wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
    int tries = 10;
    for (int i = 0; i < tries; i++) {
      WebResponse response;
      try {
        System.out.println("Pinging " + pingUrl + " - try #" + i);
        response = wc.getPage(pingUrl).getWebResponse();
        if (response.getStatusCode() == 200) return;
      } catch (Exception e) {
        // ignored
      }
      ThreadUtil.reallySleep(2000);
    }

    List<CharSequence> hits = Grep
        .grep("^SEVERE: WEB0610: WebModule \\[/web1\\] failed to deploy and has been disabled$", nodeLogFile);
    if (!hits.isEmpty()) { throw new RetryException("web1"); }

    throw new RetryException("ping app failed to deploy");
  }

  protected void waitForAppInstanceRunning(final AppServerParameters params) throws Exception {
    long timeout = START_STOP_TIMEOUT + System.currentTimeMillis();
    while (System.currentTimeMillis() < timeout) {
      String status = getAppInstanceStatus(params);
      System.out.println("Status of " + params.instanceName() + " is " + status);
      if ("running".equals(status) || status.contains("Running")) {
        break;
      }
      System.out.println("Sleeping for 2 sec before checking again...");
      ThreadUtil.reallySleep(2000);
    }
  }

  private String getAppInstanceStatus(final AppServerParameters params) throws Exception {
    File asAdminScript = getAsadminScript();
    List cmd = new ArrayList();
    cmd.add(asAdminScript.getAbsolutePath());
    cmd.add("list-domains");
    cmd.add("--domaindir=" + sandboxDirectory());

    Result result = Exec.execute((String[]) cmd.toArray(new String[] {}), null, null, asAdminScript.getParentFile());

    /**
     * Output should be something like this: <instance_name> <status> where <instance_name> is the name of the instance
     * <status> is one of {not running, running, starting}
     */
    // System.out.println("list-domains output: \n" + result.getStdout());
    if (result.getStderr().trim().length() > 0) {
      System.out.println("Error getting app status, will retry: " + result.getStderr());
    }
    System.out.flush();

    if (result.getExitCode() != 0) { return "error"; }

    return getStatus(params.instanceName(), result.getStdout());
  }

  private String getStatus(final String instanceName, final String output) {
    int start = output.indexOf(instanceName);
    if (start < 0) { return ""; }
    String line = output.substring(start);

    int end = line.indexOf("\n");
    if (end < 0) { throw new RuntimeException("no end: " + line); }
    line = line.substring(0, end).trim();

    final int delim = line.indexOf(" ");
    String appName = line.substring(0, delim);
    String status = line.substring(delim + 1);
    Assert.assertEquals(appName, instanceName);

    return status.trim();
  }

  private static byte[] startupInput() {
    try {
      String eol = System.getProperty("line.separator");

      final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      bytes.write((ADMIN_USER + eol + PASSWD + eol + PASSWD + eol).getBytes());
      if (Os.isWindows()) {
        bytes.write((byte) 26); // ctrl-Z
      } else {
        bytes.write((byte) 4); // ctrl-D
      }

      return bytes.toByteArray();
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  protected void deployWars(final File nodeLogFile, final Map wars) throws Exception {
    for (Iterator iter = wars.entrySet().iterator(); iter.hasNext();) {
      Map.Entry entry = (Entry) iter.next();
      String warName = (String) entry.getKey();
      File warFile = (File) entry.getValue();
      deployWar(warName, warFile, nodeLogFile);
    }

    // deploy the ping app so we can test to see
    // if wars are ready
    deployWar(PINGWAR, createPingWarFile(PINGWAR + ".war"), nodeLogFile);
  }

  private File createPingWarFile(final String warName) throws Exception {
    DeploymentBuilder builder = new WARBuilder(warName, new File(sandboxDirectory(), "war"));
    builder.addResourceFullpath("/com/tc/test/server/appserver/glassfish", "index.html", "index.html");
    return builder.makeDeployment().getFileSystemPath().getFile();
  }

  private void deployWar(final String warName, final File warFile, final File nodeLogFile) throws IOException,
      Exception {
    System.out.println("Deploying war [" + warName + "] on " + instanceDir.getName());

    List cmd = new ArrayList();
    cmd.add(getAsadminScript().getAbsolutePath());
    cmd.add("--interactive=false");
    cmd.add("--user");
    cmd.add(ADMIN_USER);
    cmd.add("--passwordfile");
    cmd.add(getPasswdFile().getAbsolutePath());
    cmd.add("--port=" + adminPort);
    cmd.add("deploy");
    cmd.add("--contextroot=" + warName);
    cmd.add(warFile.getAbsolutePath());

    Result result = Exec.execute((String[]) cmd.toArray(new String[] {}));

    if (result.getExitCode() == 0) {
      System.out.println("Deployed war file successfully (supposedly).");
      System.out.println(result);
      return;
    }

    // deploy failed. Stop the process and see if it the known "web1" problem
    if (process != null) {
      process.destroy();
      process = null;
    }

    ThreadUtil.reallySleep(3000);
    List<CharSequence> hits = Grep
        .grep("^SEVERE: WEB0610: WebModule \\[/web1\\] failed to deploy and has been disabled$", nodeLogFile);
    if (!hits.isEmpty()) { throw new RetryException(result.toString()); }

    if (result.getStderr().contains("Remote server does not listen for requests on")) { throw new RetryException(
                                                                                                                 result
                                                                                                                     .toString()); }

    // Generic deploy failure
    throw new RuntimeException("Deploy failed for " + warName + ": " + result);
  }

  abstract protected File getStartScript(AppServerParameters params);

  abstract protected File getStopScript(AppServerParameters params);

  abstract protected String[] getDisplayCommand(String script, AppServerParameters params);

  protected String[] getStartupCommand(final AppServerParameters params) throws Exception {
    File startScript = getStartScript(params);

    String[] displayCommand = getDisplayCommand(startScript.getAbsolutePath(), params);
    if (Os.isUnix()) {
      // This is an attempt to workaround "cannot execute" IOExceptions we see
      // on the monkey
      // The problem does NOT appear to be a lack of having execute permission
      // on the script
      String[] tmp = new String[displayCommand.length + 1];
      tmp[0] = "/bin/sh";
      System.arraycopy(displayCommand, 0, tmp, 1, displayCommand.length);
      displayCommand = tmp;
    }

    Result result = Exec.execute(displayCommand, null, null, startScript.getParentFile());

    if (result.getExitCode() != 0) { throw new RuntimeException("error executing startserv script: " + result); }

    String output = result.getStdout().trim();

    System.out.println(output);

    if (!output.startsWith("STARTOFCOMMAND|") || !output.endsWith("|ENDOFCOMMAND|")) { throw new RuntimeException(
                                                                                                                  "cannot parse output: "
                                                                                                                      + output); }

    output = output.substring("STARTOFCOMMAND|".length());
    output = output.substring(0, output.length() - "|ENDOFCOMMAND|".length());

    List cmd = new ArrayList(Arrays.asList(output.split("\\|")));

    modifyStartupCommand(cmd);

    // add the linked java process stuff to classpath
    for (int i = 0; i < cmd.size(); i++) {
      String s = (String) cmd.get(i);

      if (s.toLowerCase().trim().equals("-classpath") || s.toLowerCase().trim().equals("-cp")) {
        // the classpath is set with java.class.path system property, check
        // these just for good measure
        throw new RuntimeException("unexpected classpath arguments in startup command " + cmd);
      }
    }

    String mainArg = (String) cmd.remove(cmd.size() - 1);
    String mainClass = (String) cmd.remove(cmd.size() - 1);

    if (!"com.sun.enterprise.server.PELaunch".equals(mainClass)) { throw new RuntimeException("Unexpected main class: "
                                                                                              + mainClass); }
    if (!"start".equals(mainArg)) { throw new RuntimeException("unexpected main argument: " + mainArg); }

    cmd.add(mainClass);
    cmd.add(mainArg);

    cmd.add(0, JAVA_CMD);

    System.out.println("XXX startup command: " + cmd);
    return (String[]) cmd.toArray(new String[] {});
  }

  protected void modifyStartupCommand(final List cmd) {
    //
  }

  protected void modifyDomainConfig(final AppServerParameters params) throws Exception {
    File domainXML = getInstanceFile("config/domain.xml");

    System.out.println("Modifying domain configuration at " + domainXML.getAbsolutePath());

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

    // disable the resolve of the external DTD -- The monkey failed once since
    // it timed out talking to sun's web site
    factory.setAttribute("http://apache.org/xml/features/nonvalidating/load-external-dtd", Boolean.FALSE);

    DocumentBuilder builder = factory.newDocumentBuilder();
    Document document = builder.parse(domainXML);

    NodeList list = document.getElementsByTagName("java-config");

    // starting glassfish-3.x, this assertion is no longer true
    // there are multiple <config> allowed in domain.xml and each can have its own java-config
    // if (list.getLength() != 1) { throw new RuntimeException("wrong number of elements " + list.getLength()); }

    // you're only blindingly getting the first node, could be not the one you
    Node javaConfig = list.item(0);

    // if you want debugging of the spawned glassfish, play with this
    if (false) {
      NamedNodeMap attrs = javaConfig.getAttributes();
      attrs.getNamedItem("debug-enabled").setNodeValue("true");
      attrs.getNamedItem("debug-options")
          .setNodeValue("-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000");
    }

    appendDSOParams(document, javaConfig, params);

    TransformerFactory transformerFactory = new TransformerFactoryImpl();
    Transformer transformer = transformerFactory.newTransformer();

    if (document.getDoctype() != null) {
      transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, document.getDoctype().getPublicId());
      transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, document.getDoctype().getSystemId());
    }

    StringWriter sw = new StringWriter();
    transformer.transform(new DOMSource(document), new StreamResult(sw));

    FileUtils.writeStringToFile(domainXML, sw.toString(), "UTF-8");
  }

  private void appendDSOParams(final Document doc, final Node node, final AppServerParameters params) {
    String[] jvmArgs = params.jvmArgs().replaceAll("'", "").split("\\s");

    for (String arg : jvmArgs) {
      Element element = doc.createElement("jvm-options");
      element.appendChild(doc.createTextNode(arg));
      node.appendChild(element);
    }

    // workaround for DSO early initialization of NIO stuff
    // XXX: when/if this can be magically worked around, this option can removed
    Element element = doc.createElement("jvm-options");
    element.appendChild(doc.createTextNode("-Dcom.sun.enterprise.server.ss.ASQuickStartup=false"));
    node.appendChild(element);
  }

  @Override
  public void stop(final ServerParameters rawParams) throws Exception {
    AppServerParameters params = (AppServerParameters) rawParams;
    System.out.println("Stopping instance on port " + httpPort + "...");

    File stopScript = getStopScript(params);
    Result result = Exec.execute(new String[] { stopScript.getAbsolutePath() }, null, null, stopScript.getParentFile());
    if (result.getExitCode() != 0) {
      System.out.println(result);
    }

    if (runner != null) {
      runner.join(START_STOP_TIMEOUT);
      if (runner.isAlive()) {
        Banner.errorBanner("instance still running on port " + httpPort);
      } else {
        System.out.println("Stopped instance on port " + httpPort);
      }
    }

  }

}
