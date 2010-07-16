/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.cargo.container.deployable.WAR;
import org.codehaus.cargo.container.property.RemotePropertySet;
import org.codehaus.cargo.container.tomcat.Tomcat5xRemoteContainer;
import org.codehaus.cargo.container.tomcat.Tomcat5xRemoteDeployer;
import org.codehaus.cargo.container.tomcat.TomcatPropertySet;
import org.codehaus.cargo.container.tomcat.TomcatRuntimeConfiguration;
import org.codehaus.cargo.util.log.SimpleLogger;
import org.springframework.remoting.RemoteLookupFailureException;
import org.springframework.remoting.httpinvoker.CommonsHttpInvokerRequestExecutor;
import org.springframework.remoting.httpinvoker.HttpInvokerProxyFactoryBean;
import org.springframework.remoting.httpinvoker.HttpInvokerServiceExporter;
import org.springframework.remoting.rmi.RmiProxyFactoryBean;
import org.springframework.remoting.rmi.RmiServiceExporter;
import org.xml.sax.SAXException;

import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebResponse;
import com.tc.asm.ClassAdapter;
import com.tc.asm.ClassReader;
import com.tc.asm.ClassVisitor;
import com.tc.asm.ClassWriter;
import com.tc.asm.MethodAdapter;
import com.tc.asm.MethodVisitor;
import com.tc.asm.Opcodes;
import com.tc.management.JMXConnectorProxy;
import com.tc.test.AppServerInfo;
import com.tc.test.TestConfigObject;
import com.tc.test.server.ServerResult;
import com.tc.test.server.appserver.AppServer;
import com.tc.test.server.appserver.AppServerFactory;
import com.tc.test.server.appserver.AppServerInstallation;
import com.tc.test.server.appserver.StandardAppServerParameters;
import com.tc.test.server.util.AppServerUtil;
import com.tc.text.Banner;
import com.tc.util.runtime.Os;
import com.tc.util.runtime.ThreadDump;
import com.tc.util.runtime.Vm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import javax.management.MBeanServerConnection;

import junit.framework.Assert;

public class GenericServer extends AbstractStoppable implements WebApplicationServer {
  private static final String               ENABLED_DSO_PROPERTY = "tc.tests.info.appserver.dso.enabled";
  private static final Log                  LOG                  = LogFactory.getLog(GenericServer.class);
  private static final String               SERVER               = "server_";
  private static final boolean              GC_LOGGING           = true;
  private static final boolean              ENABLE_DEBUGGER      = Boolean.getBoolean(GenericServer.class.getName()
                                                                                      + ".ENABLE_DEBUGGER");
  private static final ThreadLocal          dsoEnabled           = new ThreadLocal() {
                                                                   @Override
                                                                   protected Object initialValue() {
                                                                     return Boolean.valueOf(System
                                                                         .getProperty(ENABLED_DSO_PROPERTY, "true"));
                                                                   }
                                                                 };

  private static final boolean              DEBUG_URL_CLASS_PATH = true;

  private final int                         jmxRemotePort;
  private final int                         rmiRegistryPort;
  private final AppServerFactory            factory;
  private AppServer                         server;
  private final StandardAppServerParameters parameters;
  private ServerResult                      result;
  private final AppServerInstallation       installation;
  private final Map                         proxyBuilderMap      = new HashMap();
  private ProxyBuilder                      proxyBuilder         = null;
  private final File                        workingDir;
  private final String                      serverInstanceName;
  private final File                        tcConfigFile;
  private final int                         appId;

  public GenericServer(final TestConfigObject config, final AppServerFactory factory,
                       final AppServerInstallation installation, final File tcConfigFile, final int serverId,
                       final File tempDir) throws Exception {
    this.factory = factory;
    this.installation = installation;
    this.rmiRegistryPort = AppServerUtil.getPort();
    this.jmxRemotePort = AppServerUtil.getPort();
    this.serverInstanceName = SERVER + serverId;
    this.parameters = (StandardAppServerParameters) factory.createParameters(serverInstanceName);
    this.workingDir = new File(installation.sandboxDirectory(), serverInstanceName);
    this.tcConfigFile = tcConfigFile;

    File bootJarFile = new File(config.normalBootJar());

    if (DEBUG_URL_CLASS_PATH) {
      if (Vm.isJDK16() && !dsoEnabled()) {
        parameters.appendJvmArgs("-Xbootclasspath/p:" + createDebugJar());
      }
    }

    if (dsoEnabled()) {
      parameters.appendSysProp("tc.base-dir", System.getProperty(TestConfigObject.TC_BASE_DIR));
      parameters.appendSysProp("com.tc.l1.modules.repositories", System.getProperty("com.tc.l1.modules.repositories"));

      parameters.appendSysProp("tc.config", this.tcConfigFile.getAbsolutePath());

      parameters.appendJvmArgs("-Xbootclasspath/p:" + bootJarFile.getAbsolutePath());
      parameters.appendSysProp("tc.classpath", writeTerracottaClassPathFile());
    }

    if (!Vm.isIBM() && !(Os.isMac() && Vm.isJDK14())) {
      parameters.appendJvmArgs("-XX:+HeapDumpOnOutOfMemoryError");
    }

    appId = config.appServerId();
    // glassfish fails with these options on
    if (appId != AppServerInfo.GLASSFISH) {
      parameters.appendSysProp("com.sun.management.jmxremote");
      parameters.appendSysProp("com.sun.management.jmxremote.authenticate", false);
      parameters.appendSysProp("com.sun.management.jmxremote.ssl", false);
      parameters.appendSysProp("com.sun.management.jmxremote.port", this.jmxRemotePort);
    }

    parameters.appendSysProp("com.tc.session.debug.sessions", true);
    parameters.appendSysProp("rmi.registry.port", this.rmiRegistryPort);

    String[] params = { "tc.classloader.writeToDisk", "tc.objectmanager.dumpHierarchy", "aspectwerkz.deployment.info",
        "aspectwerkz.details", "aspectwerkz.gen.closures", "aspectwerkz.dump.pattern", "aspectwerkz.dump.closures",
        "aspectwerkz.dump.factories", "aspectwerkz.aspectmodules" };
    for (String param : params) {
      if (Boolean.getBoolean(param)) {
        parameters.appendSysProp(param, true);
      }
    }

    enableDebug(serverId);

    // app server specific system props
    switch (appId) {
      case AppServerInfo.TOMCAT:
      case AppServerInfo.JBOSS:
        parameters.appendJvmArgs("-Djvmroute=" + serverInstanceName);
        parameters.appendJvmArgs("-XX:MaxPermSize=128m");
        parameters.appendJvmArgs("-Xms128m -Xmx192m");
        break;
      case AppServerInfo.WEBLOGIC:
        // bumped up because ContainerHibernateTest was failing with WL 9
        parameters.appendJvmArgs("-XX:MaxPermSize=128m");
        parameters.appendJvmArgs("-Xms128m -Xmx192m");
        break;
      case AppServerInfo.GLASSFISH:
        // bumped up because ContainerHibernateTest, ContinuationsTest was failing with glassfish-v1
        parameters.appendJvmArgs("-XX:MaxPermSize=128m");
        parameters.appendJvmArgs("-Xms128m -Xmx192m");
        // parameters.appendJvmArgs("-XX:+PrintGCDetails");
        break;
      case AppServerInfo.WEBSPHERE:
        parameters.appendSysProp("javax.management.builder.initial", "");
        parameters.appendJvmArgs("-XX:MaxPermSize=128m");
        parameters.appendJvmArgs("-Xms128m -Xmx192m");
        break;
    }

    if (Os.isUnix() && new File("/dev/urandom").exists()) {
      // prevent hangs reading from /dev/random
      parameters.appendSysProp("java.security.egd", "file:/dev/./urandom");
    }

    if (TestConfigObject.getInstance().isSpringTest()) {
      LOG.debug("Creating proxy for Spring test...");
      proxyBuilderMap.put(RmiServiceExporter.class, new RMIProxyBuilder());
      proxyBuilderMap.put(HttpInvokerServiceExporter.class, new HttpInvokerProxyBuilder());
    }
  }

  private String createDebugJar() {
    try {
      String classFile = "sun/misc/URLClassPath$Loader.class";

      ClassReader reader = new ClassReader(ClassLoader.getSystemResource(classFile).openStream());
      ClassWriter writer = new ClassWriter(0);
      LoaderAdapter adapter = new LoaderAdapter(writer);

      reader.accept(adapter, 0);

      File jar = new File(workingDir.getParentFile(), "debug-" + serverInstanceName + ".jar");
      jar.deleteOnExit();

      JarOutputStream out = new JarOutputStream(new FileOutputStream(jar));
      out.putNextEntry(new ZipEntry(classFile));
      out.write(writer.toByteArray());
      out.close();

      return jar.getAbsolutePath();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static boolean dsoEnabled() {
    return ((Boolean) dsoEnabled.get()).booleanValue();
  }

  public static void setDsoEnabled(final boolean b) {
    dsoEnabled.set(Boolean.valueOf(b));
  }

  public StandardAppServerParameters getServerParameters() {
    return parameters;
  }

  public int getPort() {
    if (result == null) { throw new IllegalStateException("Server has not started."); }
    return result.serverPort();
  }

  private void enableDebug(final int serverId) {
    if (GC_LOGGING && !Vm.isIBM() && appId != AppServerInfo.WEBSPHERE) {
      parameters.appendJvmArgs("-verbose:gc");
      parameters.appendJvmArgs("-XX:+PrintGCDetails");
      parameters.appendJvmArgs("-XX:+PrintGCTimeStamps");
      parameters.appendJvmArgs("-Xloggc:"
                               + new File(this.installation.sandboxDirectory(), serverInstanceName + "-gc.log")
                                   .getAbsolutePath());
    }

    if (ENABLE_DEBUGGER) {
      int debugPort = 8000 + serverId;
      if (appId == AppServerInfo.WEBSPHERE) {
        parameters.appendJvmArgs("-Xdebug -Xnoagent -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address="
                                 + debugPort + " -Djava.compiler=NONE");
      } else {
        parameters.appendJvmArgs("-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=" + debugPort);
      }
      parameters.appendSysProp("aspectwerkz.transform.verbose", true);
      parameters.appendSysProp("aspectwerkz.transform.details", true);
      Banner.warnBanner("Waiting for debugger to connect on port " + debugPort);
    }
  }

  private class RMIProxyBuilder implements ProxyBuilder {
    public Object createProxy(final Class serviceType, final String url, final Map initialContext) throws Exception {
      String rmiURL = "rmi://localhost:" + rmiRegistryPort + "/" + url;
      LOG.debug("Getting proxy for: " + rmiRegistryPort + " on " + result.serverPort());
      Exception e = null;
      for (int i = 5; i > 0; i--) {
        try {
          RmiProxyFactoryBean prfb = new RmiProxyFactoryBean();
          prfb.setServiceUrl(rmiURL);
          prfb.setServiceInterface(serviceType);
          prfb.afterPropertiesSet();
          return prfb.getObject();
        } catch (RemoteLookupFailureException lookupException) {
          e = lookupException;
        }
        Thread.sleep(30 * 1000L);
      }
      throw e;
    }
  }

  public class HttpInvokerProxyBuilder implements ProxyBuilder {
    private HttpClient client;

    public Object createProxy(final Class serviceType, final String url, final Map initialContext) throws Exception {
      String serviceURL = "http://localhost:" + result.serverPort() + "/" + url;
      LOG.debug("Getting proxy for: " + serviceURL);
      HttpInvokerProxyFactoryBean prfb = new HttpInvokerProxyFactoryBean();
      prfb.setServiceUrl(serviceURL);
      prfb.setServiceInterface(serviceType);
      CommonsHttpInvokerRequestExecutor executor;
      if (initialContext != null) {
        client = (HttpClient) initialContext.get(ProxyBuilder.HTTP_CLIENT_KEY);
      }

      if (client == null) {
        executor = new CommonsHttpInvokerRequestExecutor();
        client = executor.getHttpClient();
        if (initialContext != null) {
          initialContext.put(ProxyBuilder.HTTP_CLIENT_KEY, client);
        }
      } else {
        executor = new CommonsHttpInvokerRequestExecutor(client);
      }

      prfb.setHttpInvokerRequestExecutor(executor);
      prfb.afterPropertiesSet();
      return prfb.getObject();
    }

    public HttpClient getClient() {
      return client;
    }

    public void setClient(final HttpClient client) {
      this.client = client;
    }
  }

  public Object getProxy(final Class serviceType, final String url) throws Exception {
    if (this.proxyBuilder != null) { return proxyBuilder.createProxy(serviceType, url, null); }
    Map initCtx = new HashMap();
    initCtx.put(ProxyBuilder.EXPORTER_TYPE_KEY, RmiServiceExporter.class);
    return getProxy(serviceType, url, initCtx);
  }

  public Object getProxy(final Class serviceType, final String url, final Map initialContext) throws Exception {
    Class exporterClass = (Class) initialContext.get(ProxyBuilder.EXPORTER_TYPE_KEY);
    this.proxyBuilder = (ProxyBuilder) proxyBuilderMap.get(exporterClass);
    return this.proxyBuilder.createProxy(serviceType, url, initialContext);
  }

  public MBeanServerConnection getMBeanServerConnection() throws Exception {
    JMXConnectorProxy jmxConnectorProxy = new JMXConnectorProxy("localhost", this.jmxRemotePort);
    return jmxConnectorProxy.getMBeanServerConnection();
  }

  public WebApplicationServer addWarDeployment(final Deployment warDeployment, final String context) {
    parameters.addWar(context, warDeployment.getFileSystemPath().getFile());
    return this;
  }

  @Override
  protected void doStart() throws Exception {
    try {
      result = getAppServer().start(parameters);
    } catch (Exception e) {
      dumpThreadsAndRethrow(e);
    }
  }

  private void dumpThreadsAndRethrow(final Exception e) throws Exception {
    try {
      ThreadDump.dumpAllJavaProcesses(3, 1000);
    } catch (Throwable t) {
      t.printStackTrace();
    } finally {
      if (true) throw e; // if (true) used to silence warning
    }
  }

  @Override
  protected void doStop() throws Exception {
    try {
      server.stop(parameters);
    } catch (Exception e) {
      dumpThreadsAndRethrow(e);
    }
  }

  /**
   * url: /<CONTEXT>/<MAPPING>?params=etc
   */
  public WebResponse ping(final String url) throws MalformedURLException, IOException, SAXException {
    return ping(url, new WebConversation());
  }

  /**
   * url: /<CONTEXT>/<MAPPING>?params=etc
   */
  public WebResponse ping(final String url, final WebConversation wc) throws MalformedURLException, IOException,
      SAXException {
    String fullURL = "http://localhost:" + result.serverPort() + url;
    LOG.debug("Getting page: " + fullURL);

    wc.setExceptionsThrownOnErrorStatus(false);
    WebResponse response = wc.getResponse(fullURL);
    Assert.assertEquals("Server error:\n" + response.getText(), 200, response.getResponseCode());
    LOG.debug("Got page: " + fullURL);
    return response;
  }

  public void redeployWar(final Deployment warDeployment, final String context) {
    getRemoteDeployer().redeploy(makeWar(context, warDeployment.getFileSystemPath()));
  }

  public void deployWar(final Deployment warDeployment, final String context) {
    getRemoteDeployer().deploy(makeWar(context, warDeployment.getFileSystemPath()));
  }

  public void undeployWar(final Deployment warDeployment, final String context) {
    getRemoteDeployer().undeploy(makeWar(context, warDeployment.getFileSystemPath()));
  }

  // TODO - CARGO specific code

  private WAR makeWar(final String warContext, final FileSystemPath warPath) {
    WAR war = new WAR(warPath.toString());
    war.setContext(warContext);
    war.setLogger(new SimpleLogger());
    return war;
  }

  // TODO - Tomcat specific code

  private Tomcat5xRemoteDeployer getRemoteDeployer() {
    TomcatRuntimeConfiguration runtimeConfiguration = new TomcatRuntimeConfiguration();
    runtimeConfiguration.setProperty(RemotePropertySet.USERNAME, "admin");
    runtimeConfiguration.setProperty(RemotePropertySet.PASSWORD, "");
    runtimeConfiguration.setProperty(TomcatPropertySet.MANAGER_URL, "http://localhost:" + result.serverPort()
                                                                    + "/manager");

    Tomcat5xRemoteContainer remoteContainer = new Tomcat5xRemoteContainer(runtimeConfiguration);
    Tomcat5xRemoteDeployer deployer = new Tomcat5xRemoteDeployer(remoteContainer);
    return deployer;
  }

  // end tomcat specific code

  private String writeTerracottaClassPathFile() {
    FileOutputStream fos = null;

    try {
      File tempFile = new File(installation.sandboxDirectory(), "tc-classpath." + parameters.instanceName());
      fos = new FileOutputStream(tempFile);

      // XXX: total hack to make RequestCountTest pass on 1.4 VMs
      String[] paths = System.getProperty("java.class.path").split(File.pathSeparator);
      StringBuffer cp = new StringBuffer();
      for (String path : paths) {
        if (path.endsWith("jboss-jmx-4.0.5.jar")) {
          continue;
        }
        cp.append(path).append(File.pathSeparatorChar);
      }

      fos.write(cp.toString().getBytes());

      return tempFile.toURI().toString();
    } catch (IOException ioe) {
      throw new AssertionError(ioe);
    } finally {
      IOUtils.closeQuietly(fos);
    }

  }

  public Server restart() throws Exception {
    stop();
    start();
    return this;
  }

  @Override
  public String toString() {
    return "Generic Server" + (result != null ? "; port:" + result.serverPort() : "");
  }

  public File getWorkingDirectory() {
    return workingDir;
  }

  public AppServer getAppServer() {
    if (server == null) {
      server = factory.createAppServer(installation);
    }
    return server;
  }

  public File getTcConfigFile() {
    return tcConfigFile;
  }

  private static class LoaderAdapter extends ClassAdapter implements Opcodes {

    public LoaderAdapter(ClassVisitor cv) {
      super(cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
      return new LoaderMethodAdapter(super.visitMethod(access, name, desc, signature, exceptions));
    }

    @Override
    public void visitEnd() {
      MethodVisitor mv = super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
      mv.visitCode();
      mv.visitFieldInsn(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
      mv.visitLdcInsn("\n\n************\nWARNING: DEBUG URLClasspath$Loader in use!!!\n************\n\n");
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V");
      mv.visitInsn(RETURN);
      mv.visitMaxs(2, 0);
      mv.visitEnd();

      super.visitEnd();
    }

    private static class LoaderMethodAdapter extends MethodAdapter implements Opcodes {

      public LoaderMethodAdapter(MethodVisitor mv) {
        super(mv);
      }

      @Override
      public void visitMethodInsn(int opcode, String owner, String name, String desc) {

        if ((opcode == INVOKESPECIAL) && ("java/lang/IllegalArgumentException".equals(owner)) && "<init>".equals(name)
            && ("(Ljava/lang/String;)V".equals(desc))) {

          super.visitFieldInsn(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
          super.visitVarInsn(ALOAD, 1);
          super.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V");

          super.visitVarInsn(ALOAD, 4);
          super.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>",
                                "(Ljava/lang/String;Ljava/lang/Throwable;)V");
        } else {
          super.visitMethodInsn(opcode, owner, name, desc);
        }
      }
    }

  }

}
