/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.test.server.appserver.deployment;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class ConfigProxy implements Runnable {
  private final int        tsaPort;
  private final int        proxyTsaPort;
  private final int        configProxyPort;

  public ConfigProxy(int tsaPort, int proxyTsaPort, int configProxyPort) {
    this.tsaPort = tsaPort;
    this.proxyTsaPort = proxyTsaPort;
    this.configProxyPort = configProxyPort;
  }

  public int getProxyTsaPort() {
    return proxyTsaPort;
  }

  public int getConfigProxyPort() {
    return configProxyPort;
  }

  public int getTsaPort() {
    return tsaPort;
  }

  public void start() {
    Thread t = new Thread(this);
    t.setName("Config Proxy");
    t.setDaemon(true);
    t.start();
    System.out.println("Config Proxy started on port " + configProxyPort);
  }


  private void handle(Socket s) {
    new Thread(new Request(tsaPort, proxyTsaPort, s)).start();
  }

  private static class Request implements Runnable {

    private final Pattern REQUEST = Pattern.compile("^GET (.*) HTTP.*$");

    private final Socket  s;
    private final int     tsaPort;
    private final int     proxyTsaPort;

    Request(int tsaPort, int proxyTsaPort, Socket s) {
      this.s = s;
      this.tsaPort = tsaPort;
      this.proxyTsaPort = proxyTsaPort;
    }

    @Override
    public void run() {
      try {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in));

        String request = null;
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.length() == 0) {
            break;
          }

          Matcher m = REQUEST.matcher(line);
          if (m.matches()) {
            if (request == null) {
              request = m.group(1);
            }
          }
        }

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse("http://localhost:" + tsaPort + request);
        NodeList tsaPorts = doc.getElementsByTagName("tsa-port");

        for (int i = 0; i < tsaPorts.getLength(); i++) {
          Node node = tsaPorts.item(i);
          node.setTextContent(String.valueOf(proxyTsaPort));
        }

        DOMSource domSource = new DOMSource(doc);
        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.transform(domSource, result);
        writer.flush();

        OutputStreamWriter outWriter = new OutputStreamWriter(out);
        // response += "HTTP/1.1 200 OK\n"; response += "Content-Type: application/xml\n\n";
        outWriter.write("HTTP/1.1 200 OK\n");
        outWriter.write("Content-Type: application/xml\n\n");
        outWriter.write(writer.toString());
        outWriter.flush();
        out.close();
        in.close();
      } catch (Throwable t) {
        t.printStackTrace();
        try {
          s.close();
        } catch (IOException ioe) {
          // ignore
        }
      }
    }

  }

  @Override
  public void run() {
    ServerSocket ss = null;
    try {
      ss = new ServerSocket(configProxyPort);
      while (true) {
        handle(ss.accept());
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      if (ss != null) {
        try {
          ss.close();
        } catch (IOException e) {
          // ignored
        }
      }
    }
  }
}
