/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.jboss_common;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.tc.test.AppServerInfo;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.util.PortChooser;
import com.tc.util.ReplaceLine;
import com.tc.util.runtime.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class JBossHelper {
  public static void startupActions(File serverDir, Collection sars, AppServerInfo appServerInfo,
                                    AppServerParameters params) throws Exception {
    if ((appServerInfo.getMajor().equals("5") && appServerInfo.getMinor().startsWith("1"))) {
      writePortsConfigJBoss51x(new PortChooser(), serverDir, appServerInfo);
    } else if (appServerInfo.getMajor().equals("6")) {
      writePortsConfigJBoss6x(new PortChooser(), serverDir, appServerInfo);
    } else {
      writePortsConfig(new PortChooser(), new File(serverDir, "conf/cargo-binding.xml"), appServerInfo);
    }

    // add server_xxx lib dir to classpath
    String slashes = Os.isWindows() ? "/" : "//";

    int classPathLine = findFirstLine(new File(serverDir, "conf/jboss-service.xml"), "^.*<classpath .*$");
    String serverLib = new File(serverDir, "lib").getAbsolutePath().replace('\\', '/');
    ReplaceLine.Token[] tokens = new ReplaceLine.Token[] { new ReplaceLine.Token(
                                                                                 classPathLine,
                                                                                 "<classpath",
                                                                                 "<classpath codebase=\"file:"
                                                                                     + slashes
                                                                                     + serverLib
                                                                                     + "\" archives=\"*\"/>\n    <classpath") };
    ReplaceLine.parseFile(tokens, new File(serverDir, "conf/jboss-service.xml"));

    File dest = new File(serverLib);
    dest.mkdirs();

    for (Iterator i = sars.iterator(); i.hasNext();) {
      File sarFile = (File) i.next();
      File deploy = new File(serverDir, "deploy");
      FileUtils.copyFileToDirectory(sarFile, deploy);
    }

    setupJvmRoute(serverDir, appServerInfo, params);
  }

  private static void setupJvmRoute(File serverDir, AppServerInfo appServerInfo, AppServerParameters params)
      throws Exception {
    if (appServerInfo.getMajor().equals("6")) {
      File serverXml = new File(serverDir, "deploy/jbossweb.sar/server.xml");
      Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(serverXml);

      // set up jvmRoute
      String jvmRoute = params.properties().getProperty("jvmRoute");
      if (jvmRoute != null) {
        NodeList engines = doc.getElementsByTagName("Engine");
        for (int i = 0; i < engines.getLength(); i++) {
          Node engine = engines.item(i);
          if ("jboss.web".equals(engine.getAttributes().getNamedItem("name").getNodeValue())) {
            ((Element) engine).setAttribute("jvmRoute", jvmRoute);
            break;
          }
        }
      }

      TransformerFactory transformerFactory = TransformerFactory.newInstance();
      Transformer transformer = transformerFactory.newTransformer();
      transformer.transform(new DOMSource(doc), new StreamResult(serverXml));
    }
  }

  @SuppressWarnings("resource")
  private static int findFirstLine(File file, String pattern) throws IOException {
    BufferedReader reader = null;

    try {
      int lineNum = 0;
      reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));

      String line;
      while ((line = reader.readLine()) != null) {
        lineNum++;
        if (line.matches(pattern)) { return lineNum; }
      }
    } finally {
      IOUtils.closeQuietly(reader);
    }

    throw new RuntimeException("pattern [" + pattern + "] not found in " + file);
  }

  private static void writePortsConfigJBoss51x(PortChooser pc, File serverDir, AppServerInfo appServerInfo)
      throws IOException {
    List<ReplaceLine.Token> tokens = new ArrayList<ReplaceLine.Token>();
    File dest = new File(serverDir, "conf/bindingservice.beans/META-INF/bindings-jboss-beans.xml");

    // line 110, 280, 451 contains ports which already handled by Cargo
    int[] lines = new int[] { 117, 124, 131, 158, 165, 174, 181, 189, 212, 219, 227, 236, 243, 251, 306, 315, 322, 332,
        340, 349 };
    for (int line : lines) {
      int port = pc.chooseRandomPort();
      tokens.add(new ReplaceLine.Token(line, "\"port\">[0-9]+", "\"port\">" + port));
    }

    ReplaceLine.parseFile(tokens.toArray(new ReplaceLine.Token[] {}), dest);

    // fix up "caculated" AJP and https ports (since they can collide and drop below 1024)
    tokens.clear();
    for (int line : new int[] { 440, 441 }) {
      int port = pc.chooseRandomPort();
      tokens.add(new ReplaceLine.Token(line, "select=\"\\$port . [0-9]+\"", "select=\"" + port + "\""));
    }

    ReplaceLine.parseFile(tokens.toArray(new ReplaceLine.Token[] {}), dest);

    // handling another file
    tokens.clear();
    dest = new File(serverDir, "deploy/ejb3-connectors-jboss-beans.xml");
    tokens.add(new ReplaceLine.Token(36, "3873", String.valueOf(pc.chooseRandomPort())));
    ReplaceLine.parseFile(tokens.toArray(new ReplaceLine.Token[] {}), dest);
  }

  private static void writePortsConfigJBoss6x(PortChooser pc, File serverDir, AppServerInfo appServerInfo)
      throws IOException {
    List<ReplaceLine.Token> tokens = new ArrayList<ReplaceLine.Token>();
    File dest = new File(serverDir, "conf/bindingservice.beans/META-INF/bindings-jboss-beans.xml");

    // randomize more ports
    int[] lines = new int[] { 107, 121, 317, 324, 341, 358, 402, 410 };
    for (int line : lines) {
      int port = pc.chooseRandomPort();
      tokens.add(new ReplaceLine.Token(line, "\"port\">[0-9]+", "\"port\">" + port));
    }

    ReplaceLine.parseFile(tokens.toArray(new ReplaceLine.Token[] {}), dest);

    // handling another file
    tokens.clear();
    dest = new File(serverDir, "deploy/ejb3-connectors-jboss-beans.xml");
    tokens.add(new ReplaceLine.Token(38, "3873", String.valueOf(pc.chooseRandomPort())));
    ReplaceLine.parseFile(tokens.toArray(new ReplaceLine.Token[] {}), dest);
  }

  private static void writePortsConfig(PortChooser pc, File dest, AppServerInfo appServerInfo) throws IOException {
    List<ReplaceLine.Token> tokens = new ArrayList<ReplaceLine.Token>();

    int rmiPort = pc.chooseRandomPort();
    int rmiObjPort = new PortChooser().chooseRandomPort();

    tokens.add(new ReplaceLine.Token(14, "(RmiPort\">[0-9]+)", "RmiPort\">" + rmiPort));
    tokens.add(new ReplaceLine.Token(50, "(port=\"[0-9]+)", "port=\"" + rmiPort));
    tokens.add(new ReplaceLine.Token(24, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));
    tokens.add(new ReplaceLine.Token(32, "(port=\"[0-9]+)", "port=\"" + rmiObjPort));
    tokens.add(new ReplaceLine.Token(64, "(port=\"[0-9]+)", "port=\"" + rmiObjPort));
    tokens.add(new ReplaceLine.Token(40, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));
    tokens.add(new ReplaceLine.Token(94, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));
    tokens.add(new ReplaceLine.Token(101, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));
    tokens.add(new ReplaceLine.Token(112, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));
    tokens.add(new ReplaceLine.Token(57, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));
    tokens.add(new ReplaceLine.Token(74, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));
    tokens.add(new ReplaceLine.Token(177, "(select=\"[^\"]+\")", "select=\"" + pc.chooseRandomPort() + "\""));
    tokens.add(new ReplaceLine.Token(178, "(select=\"[^\"]+\")", "select=\"" + pc.chooseRandomPort() + "\""));

    // XXX: This isn't great, but it will do for now. Each version of cargo-binding.xml should have it's own definition
    // for this stuff, as opposed to the conditional logic in here
    if (appServerInfo.getMajor().equals("4") && appServerInfo.getMinor().startsWith("2")) {
      tokens.add(new ReplaceLine.Token(39, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));
      tokens.add(new ReplaceLine.Token(56, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));
      tokens.add(new ReplaceLine.Token(62, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));

      int ejb3HandlerPort = pc.chooseRandomPort();
      tokens.add(new ReplaceLine.Token(170, "(:3873)", ":" + ejb3HandlerPort));
      tokens.add(new ReplaceLine.Token(172, "(port=\"[0-9]+)", "port=\"" + ejb3HandlerPort));

      tokens.add(new ReplaceLine.Token(109, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));
      tokens.add(new ReplaceLine.Token(264, "(port=\"[0-9]+)", "port=\"" + pc.chooseRandomPort()));
    }

    ReplaceLine.parseFile(tokens.toArray(new ReplaceLine.Token[] {}), dest);
  }

  // check that the wars in the deployment folder are deployed using the file naming convention of jboss
  // good for jobss 7x
  public static void waitUntilWarsDeployed(File instanceDir, long waitTime) throws Exception {
    long timeToQuit = System.currentTimeMillis() + waitTime;
    File deploymentsFolder = new File(instanceDir, "deployments");

    while (System.currentTimeMillis() < timeToQuit) {
      File[] isdeployingFiles = deploymentsFolder.listFiles(new FileFilter() {
        @Override
        public boolean accept(File pathname) {
          return (pathname.getName().endsWith(".isdeploying"));
        }
      });
      File[] deployedFiles = deploymentsFolder.listFiles(new FileFilter() {
        @Override
        public boolean accept(File pathname) {
          return (pathname.getName().endsWith(".deployed"));
        }
      });
      File[] failedDeployFiles = deploymentsFolder.listFiles(new FileFilter() {
        @Override
        public boolean accept(File pathname) {
          return (pathname.getName().endsWith(".failed"));
        }
      });
      if (isdeployingFiles == null) { throw new Exception("Deployment folder " + deploymentsFolder
                                                          + " isn't a directory"); }
      if (isdeployingFiles.length == 0) {
        if (deployedFiles.length > 0) {
          System.out.println("Successfully deployed " + deployedFiles.length + " files");
          return;
        }
        if (failedDeployFiles.length > 0) {
          System.err.println("At least one file failed to deploy, test will proceed but expect problems");
          return;
        }
        // keep waiting, we likely didn't start deploying yet
      }

      Thread.sleep(1000L);
    }
  }
}
