package com.twitter.flocktest;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.yahoo.ycsb.Client;
import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.generator.IntegerGenerator;
import com.yahoo.ycsb.generator.UniformIntegerGenerator;

import com.twitter.common.collections.Pair;
import com.twitter.common.thrift.ThriftException;
import com.twitter.flock.client.FlockClient;
import com.twitter.flock.client.FlockClientImpl;
import com.twitter.flockdb.thrift.Priority;

/**
 * Flock DB plugin for YCSB.
 *
 *
 *
 * We map YCSB operations to Flock operations as follows:
 * insert: add a new user with FLOCK_FOLLOWS_PER_USER edges
 * update: add a random edge to an existing user
 * delete: remove a random edge from an existing user
 * read: get the status of FLOCK_EDGE_CHECKS_PER_READ potential edges
 * scan: retrieve the set of followings for a user
 *
 * Note that this class makes assumptions on the key names that are generated by
 * CoreWorkload -- record keys are asuumed to have the format "userN", where 0 <= N < recordcount.
 *
 * @author John Corwin
 */
public class FlockDBClient extends DB {
  enum FlockReturnCode {
    SUCCESS(0),
    FAILED(-1),
    UNSUPPORTED(-2);

    private int code;

    public int getCode() {
      return code;
    }

    private FlockReturnCode(int code) {
      this.code = code;
    }
  }

  private static final String FLOCK_PORT = "flock.port";
  private static final int DEFAULT_FLOCK_PORT = 7915;
  private static final String FLOCK_HOSTS = "flock.hosts";
  private static final String DEFAULT_FLOCK_HOST = "localhost";
  private static final String FLOCK_MAX_CONNECTIONS_PER_HOST = "flock.max_connections_per_host";
  private static final int DEFAULT_MAX_CONNECTIONS_PER_HOST = 5;
  private static final String FLOCK_FOLLOWS_PER_USER = "flock.follows_per_user";
  private static final int DEFAULT_FOLLOWS_PER_USER = 100;
  private static final String FLOCK_INITIAL_FOLLOWS_PER_USER = "flock.initial_follows_per_user";
  private static final int DEFAULT_INITIAL_FOLLOWS_PER_USER = 25;
  private static final String FLOCK_EDGE_CHECKS_PER_READ = "flock.edge_checks_per_read";
  private static final int DEFAULT_EDGE_CHECKS_PER_READ = 5;

  private FlockClient flockClient;
  private int numUsers;
  private int initialFollowsPerUser;
  private IntegerGenerator followChooser;
  private int edgeChecksPerRead;
  private boolean initialized = false;

  @Override
  public void init() throws DBException {
    if (initialized) {
      System.err.println("Flock client connection already initialized.");
      return;
    }
    Properties props = getProperties();
    int flockPort = Integer.parseInt(props.getProperty(FLOCK_PORT,
        String.valueOf(DEFAULT_FLOCK_PORT)));
    String flockHosts = props.getProperty(FLOCK_HOSTS, DEFAULT_FLOCK_HOST);
    int maxConnectionsPerHost = Integer.valueOf(props.getProperty(FLOCK_MAX_CONNECTIONS_PER_HOST,
        String.valueOf(DEFAULT_MAX_CONNECTIONS_PER_HOST)));
    ImmutableList.Builder<InetSocketAddress> socketAddrs = ImmutableList.builder();
    for (String flappHost : flockHosts.split(",")) {
      socketAddrs.add(InetSocketAddress.createUnresolved(flappHost, flockPort));
    }
    flockClient = new FlockClientImpl(FlockClientImpl.DEFAULT_CONFIG, socketAddrs.build(),
        maxConnectionsPerHost);
    numUsers = Integer.parseInt(props.getProperty(Client.RECORD_COUNT_PROPERTY, "1"));
    int targetFollowsPerUser = Integer.parseInt(props.getProperty(FLOCK_FOLLOWS_PER_USER,
        String.valueOf(DEFAULT_FOLLOWS_PER_USER)));
    followChooser = new UniformIntegerGenerator(0, targetFollowsPerUser - 1);
    initialFollowsPerUser = Integer.parseInt(props.getProperty(FLOCK_INITIAL_FOLLOWS_PER_USER,
        String.valueOf(DEFAULT_INITIAL_FOLLOWS_PER_USER)));
    edgeChecksPerRead = Integer.valueOf(props.getProperty(FLOCK_EDGE_CHECKS_PER_READ,
        String.valueOf(DEFAULT_EDGE_CHECKS_PER_READ)));
    initialized = true;
  }

  @Override
  public int read(String table, String key, Set<String> fields, HashMap<String, String> result) {
    long userId = getUserIdFromKey(key);
    try {
      Set<Long> toCheck = Sets.newHashSet();
      for (int i = 0; i < edgeChecksPerRead; i++) {
        toCheck.add(nextFollowId(userId));
      }
      Set<Long> followings = flockClient.getFollowingsFromSet(userId, toCheck);
      int i = 0;
      for (long following : followings) {
        result.put("f" + i, String.valueOf(following));
        i++;
      }
      return FlockReturnCode.SUCCESS.getCode();
    } catch (ThriftException e) {
      e.printStackTrace();
      return FlockReturnCode.FAILED.getCode();
    }
  }

  @Override
  public int scan(String table, String startkey, int recordcount, Set<String> fields,
      Vector<HashMap<String, String>> result) {
    long userId = getUserIdFromKey(startkey);
    try {
      Set<Long> followings = flockClient.getFollowings(userId);
      int i = 0;
      HashMap<String, String> followingResults = Maps.newHashMap();
      for (long following : followings) {
        followingResults.put("f" + i, String.valueOf(following));
        i++;
      }
      result.add(followingResults);
      return FlockReturnCode.SUCCESS.getCode();
    } catch (ThriftException e) {
      e.printStackTrace();
      return FlockReturnCode.FAILED.getCode();
    }
  }

  @Override
  public int update(String table, String key, HashMap<String, String> values) {
    long userId = getUserIdFromKey(key);
    long followingId = nextFollowId(userId);
    try {
      flockClient.addEdge(FlockClient.Graph.Follows, userId, followingId);
      return FlockReturnCode.SUCCESS.getCode();
    } catch (ThriftException e) {
      e.printStackTrace();
      return FlockReturnCode.FAILED.getCode();
    }
  }

  @Override
  public int insert(String table, String key, HashMap<String, String> values) {
    long userId = getUserIdFromKey(key);
    List<Pair<Long, Long>> edges = new ArrayList<Pair<Long, Long>>(initialFollowsPerUser);
    for (int i = 0; i < initialFollowsPerUser; i++) {
      long followerId = nextFollowId(userId);
      edges.add(Pair.of(userId, followerId));
    }
    try {
      flockClient.addEdges(FlockClient.Graph.Follows, Priority.High, edges);
      return FlockReturnCode.SUCCESS.getCode();
    } catch (ThriftException e) {
      e.printStackTrace();
      return FlockReturnCode.FAILED.getCode();
    }
  }

  @Override
  public int delete(String table, String key) {
    long userId = getUserIdFromKey(key);
    long followingId = nextFollowId(userId);
    try {
      flockClient.removeEdge(FlockClient.Graph.Follows, userId, followingId);
      return FlockReturnCode.SUCCESS.getCode();
    } catch (ThriftException e) {
      e.printStackTrace();
      return FlockReturnCode.FAILED.getCode();
    }
  }

  private long getUserIdFromKey(String key) {
    return Long.valueOf(key.substring("user".length()));
  }

  private long nextFollowId(long userId) {
    long followSlot = followChooser.nextInt();
    return Hashing.hash(userId * (followSlot + 2)) % numUsers;
  }
}
