/*
 * All content copyright (c) 2003-2010 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.util;

/**
 * Terracotta Integration Module Util This should be the only source where the TIM names and versions are defined. Check
 * content of integration-modules.properties
 */
public class TimUtil {
  public static final String JETTY_6_1;

  public static final String TOMCAT_5_0;
  public static final String TOMCAT_5_5;
  public static final String TOMCAT_6_0;

  public static final String JBOSS_3_2;
  public static final String JBOSS_4_0;
  public static final String JBOSS_4_2;
  public static final String JBOSS_5_1;
  public static final String JBOSS_6_0;

  public static final String WEBLOGIC_9;
  public static final String WEBLOGIC_10;

  public static final String WASCE_1_0;

  public static final String GLASSFISH_V1;
  public static final String GLASSFISH_V2;
  public static final String GLASSFISH_V3;

  public static final String RESIN_3_1;

  static {
    JETTY_6_1 = "tim-jetty-6.1";

    TOMCAT_5_0 = "tim-tomcat-5.0";
    TOMCAT_5_5 = "tim-tomcat-5.5";
    TOMCAT_6_0 = "tim-tomcat-6.0";
    JBOSS_3_2 = "tim-jboss-3.2";
    JBOSS_4_0 = "tim-jboss-4.0";
    JBOSS_4_2 = "tim-jboss-4.2";
    JBOSS_5_1 = "tim-jboss-5.1";
    JBOSS_6_0 = "tim-jboss-6.0";
    WEBLOGIC_9 = "tim-weblogic-9";
    WEBLOGIC_10 = "tim-weblogic-10";
    WASCE_1_0 = "tim-wasce-1.0";
    GLASSFISH_V1 = "tim-glassfish-v1";
    GLASSFISH_V2 = "tim-glassfish-v2";
    GLASSFISH_V3 = "tim-glassfish-v3";
    RESIN_3_1 = "tim-resin-3.1";
  }

  private TimUtil() {
    // singleton
  }
}
