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

import com.tc.test.server.appserver.AppServerParameters;
import com.tc.test.server.appserver.ValveDefinition;
import com.tc.util.ReplaceLine;
import com.tc.util.runtime.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class TomcatStartupActions {
  private TomcatStartupActions() {
    //
  }

  public static void modifyConfig(AppServerParameters params, InstalledLocalContainer container, int catalinaPropsLine) {
    try {
      modifyConfig0(params, container, catalinaPropsLine);
      configureManager(params, container, catalinaPropsLine);
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

        NodeList contexts = doc.getElementsByTagName("Context");
        for (int i = 0, n = contexts.getLength(); i < n; i++) {
          Node context = contexts.item(i);

          for (ValveDefinition def : valves) {
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

  private static void configureManager(AppServerParameters params, InstalledLocalContainer container,
                                       int catalinaPropsLine) throws Exception {
    File managerApp = new File(container.getHome(), "webapps/manager");
    String managerXml = "<Context path='/manager' debug='0' privileged='true' docBase='" + managerApp.getAbsolutePath()
                        + "'></Context>";
    File managerContextFile = new File(container.getConfiguration().getHome(), "/conf/Catalina/localhost/manager.xml");
    managerContextFile.getParentFile().mkdirs();
    FileOutputStream out = new FileOutputStream(managerContextFile);
    try {
      IOUtils.write(managerXml, out);
    } finally {
      IOUtils.closeQuietly(out);
    }
  }
}
