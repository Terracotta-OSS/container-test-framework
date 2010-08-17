/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver;

import org.apache.commons.io.IOUtils;

import com.tc.test.server.appserver.deployment.Deployment;
import com.tc.util.runtime.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * This concrete implementation allows it's creator to set values while the appserver itself interacts with the
 * immutable {@link AppServerParameters} interface.
 */
public class StandardAppServerParameters implements AppServerParameters {

  private final Map<String, Deployment>     deployments      = new HashMap<String, Deployment>();
  private final Collection                  sars             = new ArrayList();
  private final Collection<ValveDefinition> valves           = new ArrayList();
  private final Collection<String>          tomcatServerJars = new ArrayList();
  private final String                      instanceName;
  private final Properties                  props;
  private String                            jvmArgs          = "";
  private String                            classpath        = "";
  private List<String>                      extraClassPath   = new ArrayList<String>();

  public StandardAppServerParameters(String instanceName, Properties props) {
    this.instanceName = instanceName;
    this.props = props;
  }

  public void addDeployment(String context, Deployment deployment) {
    deployments.put(context, deployment);
  }

  public final void addSar(File sar) {
    sars.add(sar);
  }

  public final void addValve(ValveDefinition def) {
    valves.add(def);
  }

  public final void addTomcatServerJar(String jar) {
    tomcatServerJars.add(jar);
  }

  public final String jvmArgs() {
    return jvmArgs;
  }

  public final void appendJvmArgs(String jvmArgsVar) {
    this.jvmArgs += jvmArgsVar + " ";
  }

  public final List<String> extraClasspath() {
    return extraClassPath;
  }

  public final void appendClasspath(String classpathVar) {
    extraClassPath.add(classpathVar);
  }

  public final Map<String, File> wars() {
    Map<String, File> wars = new HashMap<String, File>();
    for (Map.Entry<String, Deployment> e : deployments.entrySet()) {
      wars.put(e.getKey(), e.getValue().getFileSystemPath().getFile());
    }
    return wars;
  }

  public Map<String, Deployment> deployments() {
    return deployments;
  }

  public final String instanceName() {
    return instanceName;
  }

  public final Properties properties() {
    return props;
  }

  public String writeTerracottaClassPathFile() {
    FileOutputStream fos = null;

    try {
      File tempFile = File.createTempFile("tc-classpath", instanceName);
      tempFile.deleteOnExit();
      fos = new FileOutputStream(tempFile);
      fos.write(System.getProperty("java.class.path").getBytes());

      String rv = tempFile.getAbsolutePath();
      if (Os.isWindows()) {
        rv = "/" + rv;
      }

      return rv;
    } catch (IOException ioe) {
      throw new AssertionError(ioe);
    } finally {
      IOUtils.closeQuietly(fos);
    }

  }

  public void appendSysProp(String name, int value) {
    appendSysProp(name, Integer.toString(value));
  }

  public void appendSysProp(String name, String value) {
    if (!name.startsWith("-")) {
      name = "-D" + name;
    }
    if (value == null) appendJvmArgs(name);
    else appendJvmArgs(name + "=" + value);
  }

  public void appendSysProp(String name) {
    appendSysProp(name, null);
  }

  public void appendSysProp(String name, boolean b) {
    appendSysProp(name, Boolean.toString(b));
  }

  public Collection sars() {
    return sars;
  }

  public Collection<ValveDefinition> valves() {
    return valves;
  }

  public Collection<String> tomcatServerJars() {
    return tomcatServerJars;
  }
}