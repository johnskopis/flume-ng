/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.flume.sink.hbase;
import java.util.ArrayList;
import java.util.List;

import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.FlumeException;
import org.hbase.async.AtomicIncrementRequest;
import org.hbase.async.PutRequest;
import org.apache.flume.conf.ComponentConfiguration;
import org.apache.flume.sink.hbase.SimpleHbaseEventSerializer.KeyType;

import com.google.common.base.Charsets;

import org.apache.commons.lang.ArrayUtils;

import java.util.Arrays;
import org.apache.hadoop.hbase.util.Bytes;
import java.util.Random;
import java.util.Map;
import java.lang.reflect.Type;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.event.EventBuilder;
import org.apache.flume.event.JSONEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.apache.flume.event.JSONEvent;
import org.apache.flume.event.EventHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple serializer to be used with the AsyncHBaseSink
 * that returns puts from an event, by writing the event
 * body into it. The headers are discarded. It also updates a row in hbase
 * which acts as an event counter.
 *
 * Takes optional parameters:<p>
 * <tt>rowPrefix:</tt> The prefix to be used. Default: <i>default</i><p>
 * <tt>incrementRow</tt> The row to increment. Default: <i>incRow</i><p>
 * <tt>suffix:</tt> <i>uuid/random/timestamp.</i>Default: <i>uuid</i><p>
 *
 * Mandatory parameters: <p>
 * <tt>cf:</tt>Column family.<p>
 * Components that have no defaults and will not be used if absent:
 * <tt>payloadColumn:</tt> Which column to put payload in. If it is not present,
 * event data will not be written.<p>
 * <tt>incrementColumn:</tt> Which column to increment. If this is absent, it
 *  means no column is incremented.
 */

public class JsonAsyncHbaseEventSerializer implements AsyncHbaseEventSerializer {
  private byte[] table;
  private byte[] cf;
  private byte[] payload;
  private byte[] payloadColumn;
  private byte[] incrementColumn;
  private String rowPrefix;
  private byte[] incrementRow;
  private KeyType keyType;
  private static final Logger LOG = LoggerFactory.getLogger(JsonAsyncHbaseEventSerializer.class);
  private final Type listType =
          new TypeToken<Map<String, String>>() {
          }.getType();
  private final Gson gson;

  public JsonAsyncHbaseEventSerializer() {
    gson = new GsonBuilder().disableHtmlEscaping().create();
  }

  public void initialize(byte[] table, byte[] cf) {
    this.table = table;
    this.cf = cf;
  }

  @Override
  public List<PutRequest> getActions() {
    List<PutRequest> actions = new ArrayList<PutRequest>();
    Map<String, String> pairs;
    long now = System.currentTimeMillis();
    long reverse_timestamp = Long.MAX_VALUE - now;


    byte[] jsonData;
    int start = 0;
    int finish = payload.length;
    for (int i = 0; i < payload.length; i++) {
      if (payload[i] == '{') {
        start = i;
      } else if (payload[i] == '}') {
        finish = i + 1;
      }
    }

    jsonData = Arrays.copyOfRange(payload, start, finish);

    try {
      pairs = gson.fromJson(new String(jsonData), listType);
    } catch (Exception e) {
      LOG.error("failed to parse json: (" + new String(jsonData) + ")");
      return actions;
    }

    Random randomGenerator = new Random();
    String bucket = Integer.toString(randomGenerator.nextInt(8));
    String eventType, userKey, rowKey;

    if (pairs.containsKey("user_key")) {
      userKey = pairs.get("user_key");
      if (pairs.containsKey("event_type")) {
        eventType = pairs.get("event_type");
        rowKey = bucket + '-' + reverse_timestamp + '-' + eventType + '-' + userKey;
      } else {
        rowKey = bucket + '-' + reverse_timestamp + '-' + '1' + userKey;
      }
    } else {
      rowKey = bucket + '-' + reverse_timestamp + '-' + '1' + "-0000000000-0000000000";
    }

    for (Map.Entry<String, String> entry : pairs.entrySet()) {
      PutRequest putRequest =  new PutRequest(table,
          Bytes.toBytes(rowKey),
          cf,
          Bytes.toBytes(entry.getKey()),
          Bytes.toBytes(entry.getValue()),
          now
      );
      actions.add(putRequest);
    }
    return actions;
  }

  public List<AtomicIncrementRequest> getIncrements(){
    List<AtomicIncrementRequest> actions = new
        ArrayList<AtomicIncrementRequest>();
    if(incrementColumn != null) {
      AtomicIncrementRequest inc = new AtomicIncrementRequest(table,
          incrementRow, cf, incrementColumn);
      actions.add(inc);
    }
    return actions;
  }

  @Override
  public void cleanUp() {
    // TODO Auto-generated method stub

  }

  @Override
  public void configure(Context context) {
    String pCol = context.getString("payloadColumn");
    String iCol = context.getString("incrementColumn");
    rowPrefix = context.getString("rowPrefix", "default");
    String suffix = context.getString("suffix", "uuid");
    if(pCol != null && !pCol.isEmpty()) {
      if(suffix.equals("timestamp")){
        keyType = KeyType.TS;
      } else if (suffix.equals("random")) {
        keyType = KeyType.RANDOM;
      } else if(suffix.equals("nano")){
        keyType = KeyType.TSNANO;
      } else {
        keyType = KeyType.UUID;
      }
      payloadColumn = pCol.getBytes(Charsets.UTF_8);
    }
    if(iCol != null && !iCol.isEmpty()) {
      incrementColumn = iCol.getBytes(Charsets.UTF_8);
    }
    incrementRow =
        context.getString("incrementRow", "incRow").getBytes(Charsets.UTF_8);
  }

  @Override
  public void setEvent(Event event) {
    this.payload = event.getBody();
  }

  @Override
  public void configure(ComponentConfiguration conf) {
    // TODO Auto-generated method stub
  }

}