/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.test.server.appserver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

public class ValveDefinition {

  private final String              className;
  private final Map<String, String> attributes   = new LinkedHashMap<String, String>();
  private boolean                   isExpressVal = false;

  public ValveDefinition(String className) {
    this.className = className;
    setAttribute("className", className);
  }

  public String getClassName() {
    return className;
  }

  public boolean isExpressVal() {
    return isExpressVal;
  }

  public void setExpressVal(boolean flag) {
    isExpressVal = flag;
  }

  public void setAttribute(String name, String value) {
    attributes.put(name, value);
  }

  public Map<String, String> getAttributes() {
    return attributes;
  }

  @Override
  public String toString() {
    return toXml();
  }

  public String toXml() {
    String xml = "<Valve";
    for (Entry<String, String> attr : attributes.entrySet()) {
      xml += " " + attr.getKey() + "=\"" + attr.getValue() + "\"";
    }

    xml += "/>";

    return xml;
  }
}
