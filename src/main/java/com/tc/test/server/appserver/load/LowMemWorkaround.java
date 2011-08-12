/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.test.server.appserver.load;

import org.hyperic.sigar.Mem;
import org.hyperic.sigar.Sigar;
import org.hyperic.sigar.SigarException;

import com.tc.statistics.retrieval.SigarUtil;
import com.tc.test.AppServerInfo;
import com.tc.text.Banner;

public class LowMemWorkaround {
  // This number isn't exact of course but it is appropriate for our monkey
  // environments
  private static final long TWO_GIGABYTES = 2000000000L;

  public static int computeNumberOfNodes(int defaultNum, int lowMemNum, AppServerInfo appServerInfo) {
    long memTotal = getMem();

    if (memTotal < TWO_GIGABYTES || appServerInfo.getId() == AppServerInfo.JBOSS) {
      Banner.warnBanner("Using " + lowMemNum + " nodes (instead of " + defaultNum
                        + ") since this machine has limited memory (" + memTotal + ") or this is a JBoss test");
      return lowMemNum;
    }

    return defaultNum;
  }

  public static boolean lessThan2Gb() {
    return getMem() < TWO_GIGABYTES;
  }

  private static long getMem() {
    try {
      SigarUtil.sigarInit();
      Sigar sigar = new Sigar();

      Mem mem = sigar.getMem();
      return mem.getTotal();
    } catch (SigarException se) {
      throw new RuntimeException(se);
    }
  }

}
