package com.launchdarkly.client;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.Gson;
import com.launchdarkly.eventsource.ConnectionErrorHandler;
import com.launchdarkly.eventsource.EventHandler;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.UnsuccessfulResponseException;

import okhttp3.Headers;

class StreamProcessor implements UpdateProcessor {
  private static final String PUT = "put";
  private static final String PATCH = "patch";
  private static final String DELETE = "delete";
  private static final String INDIRECT_PUT = "indirect/put";
  private static final String INDIRECT_PATCH = "indirect/patch";
  private static final Logger logger = LoggerFactory.getLogger(StreamProcessor.class);
  private static final int DEAD_CONNECTION_INTERVAL_MS = 300 * 1000;

  private final FeatureStore store;
  private final LDConfig config;
  private final String sdkKey;
  private final FeatureRequestor requestor;
  private volatile EventSource es;
  private AtomicBoolean initialized = new AtomicBoolean(false);


  StreamProcessor(String sdkKey, LDConfig config, FeatureRequestor requestor) {
    this.store = config.featureStore;
    this.config = config;
    this.sdkKey = sdkKey;
    this.requestor = requestor;
  }

  @Override
  public Future<Void> start() {
    final SettableFuture<Void> initFuture = SettableFuture.create();

    Headers headers = new Headers.Builder()
        .add("Authorization", this.sdkKey)
        .add("User-Agent", "JavaClient/" + LDClient.CLIENT_VERSION)
        .add("Accept", "text/event-stream")
        .build();

    ConnectionErrorHandler connectionErrorHandler = new ConnectionErrorHandler() {
      @Override
      public Action onConnectionError(Throwable t) {
        if ((t instanceof UnsuccessfulResponseException) &&
            ((UnsuccessfulResponseException) t).getCode() == 401) {
          logger.error("Received 401 error, no further streaming connection will be made since SDK key is invalid");
          return Action.SHUTDOWN;
        }
        return Action.PROCEED;
      }
    };
    
    EventHandler handler = new EventHandler() {

      @Override
      public void onOpen() throws Exception {
      }

      @Override
      public void onClosed() throws Exception {
      }

      @Override
      public void onMessage(String name, MessageEvent event) throws Exception {
        Gson gson = new Gson();
        switch (name) {
          case PUT:
            store.init(FeatureFlag.fromJsonMap(config, event.getData()));
            if (!initialized.getAndSet(true)) {
              initFuture.set(null);
              logger.info("Initialized LaunchDarkly client.");
            }
            break;
          case PATCH: {
            FeaturePatchData data = gson.fromJson(event.getData(), FeaturePatchData.class);
            store.upsert(data.key(), data.feature());
            break;
          }
          case DELETE: {
            FeatureDeleteData data = gson.fromJson(event.getData(), FeatureDeleteData.class);
            store.delete(data.key(), data.version());
            break;
          }
          case INDIRECT_PUT:
            try {
              store.init(requestor.getAllFlags());
              if (!initialized.getAndSet(true)) {
                initFuture.set(null);
                logger.info("Initialized LaunchDarkly client.");
              }
            } catch (IOException e) {
              logger.error("Encountered exception in LaunchDarkly client", e);
            }
            break;
          case INDIRECT_PATCH:
            String key = event.getData();
            try {
              FeatureFlag feature = requestor.getFlag(key);
              store.upsert(key, feature);
            } catch (IOException e) {
              logger.error("Encountered exception in LaunchDarkly client", e);
            }
            break;
          default:
            logger.warn("Unexpected event found in stream: " + event.getData());
            break;
        }
      }

      @Override
      public void onComment(String comment) {
        logger.debug("Received a heartbeat");
      }

      @Override
      public void onError(Throwable throwable) {
        logger.error("Encountered EventSource error: " + throwable.getMessage());
        logger.debug("", throwable);
      }
    };

    EventSource.Builder builder = new EventSource.Builder(handler, URI.create(config.streamURI.toASCIIString() + "/flags"))
        .connectionErrorHandler(connectionErrorHandler)
        .headers(headers)
        .reconnectTimeMs(config.reconnectTimeMs)
        .connectTimeoutMs(config.connectTimeoutMillis)
        .readTimeoutMs(DEAD_CONNECTION_INTERVAL_MS);
    // Note that this is not the same read timeout that can be set in LDConfig.  We default to a smaller one
    // there because we don't expect long delays within any *non*-streaming response that the LD client gets.
    // A read timeout on the stream will result in the connection being cycled, so we set this to be slightly
    // more than the expected interval between heartbeat signals.

    if (config.proxy != null) {
      builder.proxy(config.proxy);
      if (config.proxyAuthenticator != null) {
        builder.proxyAuthenticator(config.proxyAuthenticator);
      }
    }

    es = builder.build();
    es.start();
    return initFuture;
  }

  @Override
  public void close() throws IOException {
    logger.info("Closing LaunchDarkly StreamProcessor");
    if (es != null) {
      es.close();
    }
    if (store != null) {
      store.close();
    }
  }

  @Override
  public boolean initialized() {
    return initialized.get();
  }

  FeatureFlag getFeature(String key) {
    return store.get(key);
  }

  private static final class FeaturePatchData {
    String path;
    FeatureFlag data;

    public FeaturePatchData() {

    }

    String key() {
      return path.substring(1);
    }

    FeatureFlag feature() {
      return data;
    }

  }

  private static final class FeatureDeleteData {
    String path;
    int version;

    public FeatureDeleteData() {

    }

    String key() {
      return path.substring(1);
    }

    int version() {
      return version;
    }

  }
}
