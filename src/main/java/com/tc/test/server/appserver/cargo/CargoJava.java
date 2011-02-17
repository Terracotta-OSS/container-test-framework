/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.cargo;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Location;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.RuntimeConfigurable;
import org.apache.tools.ant.Target;
import org.apache.tools.ant.taskdefs.Java;
import org.apache.tools.ant.types.Commandline.Argument;
import org.apache.tools.ant.types.CommandlineJava;
import org.apache.tools.ant.types.Environment;
import org.apache.tools.ant.types.Environment.Variable;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;

import com.tc.lcp.CargoLinkedChildProcess;
import com.tc.test.TestConfigObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * This class is used in the process of patching CARGO to allow it's child processes to know whether the parent process
 * is still alive and kill themselves off if need be. It is a decorator for the ANT Java task which calls
 * {@link CargoLinkedChildProcess} which in turn spawns the desired appserver instance.
 */
public final class CargoJava extends Java {

  private static final boolean DEBUG  = false;

  // this static thing is TERRIBLE, but trying to get tigher integration with Cargo is worse
  public static final Link     LINK   = new Link();

  private final Java           java;
  private Path                 classpath;
  private String               className;
  private final List           args;
  private boolean              dirSet = false;

  public CargoJava(Java java) {
    this.java = java;
    this.args = new ArrayList();
  }

  private void wrapProcess() {
    Args linkArgs;
    try {
      linkArgs = Link.take();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    File dir = linkArgs.instancePath;

    String logFile = new File(dir.getParent(), dir.getName() + ".log").getAbsolutePath();

    if (!dirSet) {
      setDir(dir);
    }

    java.setOutput(new File(logFile));
    java.setAppend(true);
    java.setFailonerror(true);

    assignWrappedArgs(linkArgs);
    TestConfigObject config = TestConfigObject.getInstance();
    classpath.setPath(classpath.toString() + File.pathSeparatorChar + config.extraClassPathForAppServer());
    java.setClassname(CargoLinkedChildProcess.class.getName());
    // java.setMaxmemory("128m");
    Environment.Variable envVar = new Environment.Variable();
    envVar.setKey("JAVA_HOME");
    envVar.setValue(System.getProperty("java.home"));
    java.addEnv(envVar);
    // Argument argument = java.createJvmarg();
    // argument.setValue("-verbose:gc");

    if (DEBUG) {
      CommandlineJava cmdLineJava = getCommandLine(this.java);
      System.err.println(cmdLineJava.describeCommand());
    }

    java.createJvmarg().setValue("-DNODE=" + dir.getName());

    java.execute();
  }

  private static CommandlineJava getCommandLine(Java j) {
    // more utter gross-ness
    try {
      Field f = j.getClass().getDeclaredField("cmdl");
      f.setAccessible(true);
      return (CommandlineJava) f.get(j);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void assignWrappedArgs(Args linkArgs) {
    java.clearArgs();
    java.createArg().setValue(this.className);
    java.createArg().setValue(Integer.toString(linkArgs.port));
    java.createArg().setValue(linkArgs.instancePath.getAbsolutePath());

    Iterator iter = this.args.iterator();
    while (iter.hasNext()) {
      String[] parts = ((Argument) iter.next()).getParts();
      for (int i = 0; i < parts.length; ++i)
        java.createArg().setValue(parts[i]);
    }
  }

  @Override
  public void addEnv(Variable arg0) {
    this.java.addEnv(arg0);
  }

  @Override
  public void addSysproperty(Variable arg0) {
    this.java.addSysproperty(arg0);
  }

  @Override
  public void clearArgs() {
    this.java.clearArgs();
  }

  @Override
  public Argument createArg() {
    Argument out = this.java.createArg();
    this.args.add(out);
    return out;
  }

  @Override
  public Path createClasspath() {
    Path path = this.java.createClasspath();
    this.classpath = path;
    return path;
  }

  @Override
  public Argument createJvmarg() {
    return this.java.createJvmarg();
  }

  @Override
  public boolean equals(Object obj) {
    return this.java.equals(obj);
  }

  @Override
  public void execute() throws BuildException {
    wrapProcess();
  }

  @Override
  public int executeJava() throws BuildException {
    return this.java.executeJava();
  }

  @Override
  public String getDescription() {
    return this.java.getDescription();
  }

  @Override
  public Location getLocation() {
    return this.java.getLocation();
  }

  @Override
  public Target getOwningTarget() {
    return this.java.getOwningTarget();
  }

  @Override
  public Project getProject() {
    return this.java.getProject();
  }

  @Override
  public RuntimeConfigurable getRuntimeConfigurableWrapper() {
    return this.java.getRuntimeConfigurableWrapper();
  }

  @Override
  public String getTaskName() {
    return this.java.getTaskName();
  }

  @Override
  public int hashCode() {
    return this.java.hashCode();
  }

  @Override
  public void init() throws BuildException {
    this.java.init();
  }

  @Override
  public void log(String arg0, int arg1) {
    this.java.log(arg0, arg1);
  }

  @Override
  public void log(String arg0) {
    this.java.log(arg0);
  }

  @Override
  public void maybeConfigure() throws BuildException {
    this.java.maybeConfigure();
  }

  @Override
  public void setAppend(boolean arg0) {
    this.java.setAppend(arg0);
  }

  @Override
  public void setArgs(String arg0) {
    this.java.setArgs(arg0);
  }

  @Override
  public void setClassname(String arg0) throws BuildException {
    this.className = arg0;
    this.java.setClassname(arg0);
  }

  @Override
  public void setClasspath(Path arg0) {
    this.java.setClasspath(arg0);
  }

  @Override
  public void setClasspathRef(Reference arg0) {
    this.java.setClasspathRef(arg0);
  }

  @Override
  public void setDescription(String arg0) {
    this.java.setDescription(arg0);
  }

  @Override
  public void setDir(File arg0) {
    this.java.setDir(arg0);
    dirSet = true;
  }

  @Override
  public void setFailonerror(boolean arg0) {
    this.java.setFailonerror(arg0);
  }

  @Override
  public void setFork(boolean arg0) {
    this.java.setFork(arg0);
  }

  @Override
  public void setJar(File arg0) throws BuildException {
    try {
      String absPath = arg0.getCanonicalFile().getParentFile().getParent();
      JarFile jar = new JarFile(arg0);
      Manifest manifest = jar.getManifest();
      Attributes attrib = manifest.getMainAttributes();

      String classPathAttrib = attrib.getValue("Class-Path");
      String absClassPath = classPathAttrib.replaceAll("^\\.\\.", absPath).replaceAll("\\s\\.\\.",
                                                                                      File.pathSeparatorChar + absPath);
      this.classpath.setPath(classpath.toString() + File.pathSeparatorChar + absClassPath);
      this.classpath.setPath(classpath.toString() + File.pathSeparator + arg0);

      // TODO: make sysprops
      // this.classpath.setPath(classpath.toString() + createExtraManifestClassPath("Endorsed-Dirs", attrib, absPath));
      // this.classpath.setPath(classpath.toString() + createExtraManifestClassPath("Extension-Dirs", attrib, absPath));

      setClassname(attrib.getValue("Main-Class"));

    } catch (IOException ioe) {
      throw new BuildException("problem reading manifest");
    }
  }

  // private String createExtraManifestClassPath(String attributeName, Attributes attrib, String absPath) {
  // String extraDirAttrib = attrib.getValue(attributeName);
  // File absExtraDir = new File(absPath + File.separator + extraDirAttrib);
  // String[] extraJars = absExtraDir.list(new FilenameFilter() {
  // public boolean accept(File dir, String name) {
  // return (name.endsWith(".jar")) ? true : false;
  // }
  // });
  // String extraClassPath = "";
  // for (int i = 0; i < extraJars.length; i++) {
  // extraClassPath += File.pathSeparatorChar + absExtraDir.toString() + File.separator + extraJars[i];
  // }
  // return extraClassPath;
  // }

  @Override
  public void setJvm(String arg0) {
    this.java.setJvm(arg0);
  }

  @Override
  public void setJvmargs(String arg0) {
    this.java.setJvmargs(arg0);
  }

  @Override
  public void setJVMVersion(String arg0) {
    this.java.setJVMVersion(arg0);
  }

  @Override
  public void setLocation(Location arg0) {
    this.java.setLocation(arg0);
  }

  @Override
  public void setMaxmemory(String arg0) {
    this.java.setMaxmemory(arg0);
  }

  @Override
  public void setNewenvironment(boolean arg0) {
    this.java.setNewenvironment(arg0);
  }

  @Override
  public void setOutput(File arg0) {
    this.java.setOutput(arg0);
  }

  @Override
  public void setOwningTarget(Target arg0) {
    this.java.setOwningTarget(arg0);
  }

  @Override
  public void setProject(Project arg0) {
    this.java.setProject(arg0);
  }

  @Override
  public void setRuntimeConfigurableWrapper(RuntimeConfigurable arg0) {
    this.java.setRuntimeConfigurableWrapper(arg0);
  }

  @Override
  public void setTaskName(String arg0) {
    this.java.setTaskName(arg0);
  }

  @Override
  public void setTimeout(Long arg0) {
    this.java.setTimeout(arg0);
  }

  @Override
  public String toString() {
    return this.java.toString();
  }

  public static class Args {
    final File instancePath;
    final int  port;

    public Args(int port, File instancePath) {
      this.port = port;
      this.instancePath = instancePath;
    }
  }

  public static class Link {
    private static Args         args = null;
    private static final Object lock = new Object();

    public static Args take() throws InterruptedException {
      synchronized (lock) {
        while (args == null) {
          lock.wait();
        }
        Args rv = args;
        args = null;
        lock.notifyAll();
        return rv;
      }
    }

    public static void put(Args putArgs) throws InterruptedException {
      synchronized (lock) {
        while (args != null) {
          lock.wait();
        }

        args = putArgs;
        lock.notifyAll();
      }
    }
  }

}
