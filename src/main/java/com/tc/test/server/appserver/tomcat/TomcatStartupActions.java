/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.test.server.appserver.tomcat;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.codehaus.cargo.container.InstalledLocalContainer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.tc.test.AppServerInfo;
import com.tc.test.TestConfigObject;
import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.ValveDefinition;
import com.tc.test.server.appserver.deployment.Deployment;
import com.tc.test.server.util.Assert;
import com.tc.util.ReplaceLine;
import com.tc.util.runtime.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class TomcatStartupActions {
  public static final String USE_NIO_PROTOCOL_KEY = "useNioProtocol";

  private TomcatStartupActions() {
    //
  }

  public static void modifyConfig(AppServerParameters params, InstalledLocalContainer container, int catalinaPropsLine) {
    try {
      modifyConfig0(params, container, catalinaPropsLine);
      if (Boolean.valueOf(params.properties().getProperty(USE_NIO_PROTOCOL_KEY))) {
        switchToNioProtocol(params, container);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void modifyConfig0(AppServerParameters params, InstalledLocalContainer container, int catalinaPropsLine)
      throws Exception {
    try {
      // add Vavles (if defined)
      Collection<ValveDefinition> valves = params.valves();
      if (!valves.isEmpty()) {

        File serverXml = new File(container.getConfiguration().getHome(), "conf/server.xml");
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(serverXml);

        Map<String, Deployment> deployments = params.deployments();
        NodeList contexts = doc.getElementsByTagName("Context");
        for (int i = 0, n = contexts.getLength(); i < n; i++) {
          Node context = contexts.item(i);
          String contextPath = context.getAttributes().getNamedItem("path").getNodeValue();
          // remove leading /
          String appContext = contextPath.substring(1);
          Deployment deployment = deployments.get(appContext);
          Assert.assertNotNull(deployment);

          for (ValveDefinition def : valves) {
            // don't write out express valve if it's not clustered
            if (def.isExpressVal() && !deployment.isClustered()) {
              continue;
            }
            Element valve = doc.createElement("Valve");
            for (Entry<String, String> attr : def.getAttributes().entrySet()) {
              valve.setAttribute(attr.getKey(), attr.getValue());
            }

            context.appendChild(valve);
          }
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.transform(new DOMSource(doc), new StreamResult(serverXml));
      }

      // add in custom server jars
      Collection<String> tomcatServerJars = params.tomcatServerJars();
      if (!tomcatServerJars.isEmpty()) {
        String jarsCsv = "";
        String[] jars = tomcatServerJars.toArray(new String[] {});
        for (int i = 0; i < jars.length; i++) {
          jarsCsv += "file:" + (Os.isWindows() ? "/" : "") + jars[i].replace('\\', '/');
          if (i < jars.length - 1) {
            jarsCsv += ",";
          }
        }

        File catalinaProps = new File(container.getConfiguration().getHome(), "conf/catalina.properties");
        FileUtils.copyFile(new File(container.getHome(), "conf/catalina.properties"), catalinaProps);

        List<ReplaceLine.Token> tokens = new ArrayList<ReplaceLine.Token>();
        tokens.add(new ReplaceLine.Token(catalinaPropsLine, ".jar$", ".jar," + jarsCsv));
        ReplaceLine.parseFile(tokens.toArray(new ReplaceLine.Token[] {}), catalinaProps);
      }
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  public static void configureManagerApp(AppServerParameters params, InstalledLocalContainer container) {
    AppServerInfo appServerInfo = TestConfigObject.getInstance().appServerInfo();
    String managerPath = "server/webapps/manager";
    if (Integer.valueOf(appServerInfo.getMajor()) >= 6) {
      managerPath = "webapps/manager";
    }
    File managerApp = new File(container.getHome(), managerPath);

    String managerXml = "<Context path='/manager' debug='0' privileged='true' docBase='" + managerApp.getAbsolutePath()
                        + "'></Context>";
    File managerContextFile = new File(container.getConfiguration().getHome(), "/conf/Catalina/localhost/manager.xml");
    managerContextFile.getParentFile().mkdirs();
    FileOutputStream out = null;

    try {
      out = new FileOutputStream(managerContextFile);
      IOUtils.write(managerXml, out);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      IOUtils.closeQuietly(out);
    }
  }

  private static void switchToNioProtocol(AppServerParameters params, InstalledLocalContainer container)
      throws Exception {
    File serverXml = new File(container.getConfiguration().getHome(), "conf/server.xml");
    Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(serverXml);
    Element connector = (Element) doc.getElementsByTagName("Connector").item(0);
    connector.setAttribute("protocol", "org.apache.coyote.http11.Http11NioProtocol");
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer transformer = transformerFactory.newTransformer();
    transformer.transform(new DOMSource(doc), new StreamResult(serverXml));
  }
}
