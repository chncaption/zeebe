/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.exporter.opensearch.aws;

import static org.apache.http.protocol.HttpCoreContext.HTTP_TARGET_HOST;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.apache.http.Header;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HttpContext;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.signer.Signer;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

/**
 * An {@link HttpRequestInterceptor} that signs requests using any AWS {@link Signer} and {@link
 * AwsCredentialsProvider}.
 *
 * <p>This interceptor was taken from the <a
 * href="https://github.com/awsdocs/amazon-opensearch-service-developer-guide/blob/master/sample_code/java/aws-request-signing-apache-interceptor/src/main/java/com/amazonaws/http/AwsRequestSigningApacheInterceptor.java">Amazon
 * Opensearch developer guide</a>
 */
public class AwsSignHttpRequestInterceptor implements HttpRequestInterceptor {
  /** The service that we're connecting to. */
  private final String service;

  /** The particular signer implementation. */
  private final Signer signer;

  /** The source of AWS credentials for signing. */
  private final AwsCredentialsProvider awsCredentialsProvider;

  /** The region signing region. */
  private final Region region;

  /**
   * @param service service that we're connecting to
   * @param signer particular signer implementation
   * @param awsCredentialsProvider source of AWS credentials for signing
   * @param region signing region
   */
  public AwsSignHttpRequestInterceptor(
      final String service,
      final Signer signer,
      final AwsCredentialsProvider awsCredentialsProvider,
      final Region region) {
    this.service = service;
    this.signer = signer;
    this.awsCredentialsProvider = awsCredentialsProvider;
    this.region = Objects.requireNonNull(region);
  }

  /**
   * @param service service that we're connecting to
   * @param signer particular signer implementation
   * @param awsCredentialsProvider source of AWS credentials for signing
   * @param region signing region
   */
  public AwsSignHttpRequestInterceptor(
      final String service,
      final Signer signer,
      final AwsCredentialsProvider awsCredentialsProvider,
      final String region) {
    this(service, signer, awsCredentialsProvider, Region.of(region));
  }

  /** {@inheritDoc} */
  @Override
  public void process(final HttpRequest request, final HttpContext context)
      throws HttpException, IOException {
    final URIBuilder uriBuilder;
    try {
      uriBuilder = new URIBuilder(request.getRequestLine().getUri());
    } catch (final URISyntaxException e) {
      throw new IOException("Invalid URI", e);
    }

    // Copy Apache HttpRequest to AWS Request
    final SdkHttpFullRequest.Builder requestBuilder =
        SdkHttpFullRequest.builder()
            .method(SdkHttpMethod.fromValue(request.getRequestLine().getMethod()))
            .uri(buildUri(context, uriBuilder));

    if (request instanceof final HttpEntityEnclosingRequest httpEntityEnclosingRequest) {
      if (httpEntityEnclosingRequest.getEntity() != null) {
        final InputStream content = httpEntityEnclosingRequest.getEntity().getContent();
        requestBuilder.contentStreamProvider(() -> content);
      }
    }
    requestBuilder.rawQueryParameters(nvpToMapParams(uriBuilder.getQueryParams()));
    requestBuilder.headers(headerArrayToMap(request.getAllHeaders()));

    final ExecutionAttributes attributes = new ExecutionAttributes();
    attributes.putAttribute(
        AwsSignerExecutionAttribute.AWS_CREDENTIALS, awsCredentialsProvider.resolveCredentials());
    attributes.putAttribute(AwsSignerExecutionAttribute.SERVICE_SIGNING_NAME, service);
    attributes.putAttribute(AwsSignerExecutionAttribute.SIGNING_REGION, region);

    // Sign it
    final SdkHttpFullRequest signedRequest = signer.sign(requestBuilder.build(), attributes);

    // Now copy everything back
    request.setHeaders(mapToHeaderArray(signedRequest.headers()));
    if (request instanceof final HttpEntityEnclosingRequest httpEntityEnclosingRequest) {
      if (httpEntityEnclosingRequest.getEntity() != null) {
        final BasicHttpEntity basicHttpEntity = new BasicHttpEntity();
        basicHttpEntity.setContent(
            signedRequest
                .contentStreamProvider()
                .orElseThrow(() -> new IllegalStateException("There must be content"))
                .newStream());
        // reset the position of the input stream as it has been read
        basicHttpEntity.getContent().reset();
        // wrap into repeatable entity to support retries
        httpEntityEnclosingRequest.setEntity(new BufferedHttpEntity(basicHttpEntity));
      }
    }
  }

  private URI buildUri(final HttpContext context, final URIBuilder uriBuilder) throws IOException {
    try {
      final HttpHost host = (HttpHost) context.getAttribute(HTTP_TARGET_HOST);

      if (host != null) {
        uriBuilder.setHost(host.getHostName());
        uriBuilder.setScheme(host.getSchemeName());
        uriBuilder.setPort(host.getPort());
      }

      return uriBuilder.build();
    } catch (final URISyntaxException e) {
      throw new IOException("Invalid URI", e);
    }
  }

  /**
   * @param params list of HTTP query params as NameValuePairs
   * @return a multimap of HTTP query params
   */
  private static Map<String, List<String>> nvpToMapParams(final List<NameValuePair> params) {
    final Map<String, List<String>> parameterMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (final NameValuePair nvp : params) {
      final List<String> argsList =
          parameterMap.computeIfAbsent(nvp.getName(), k -> new ArrayList<>());
      argsList.add(nvp.getValue());
    }
    return parameterMap;
  }

  /**
   * @param headers modelled Header objects
   * @return a Map of header entries
   */
  private static Map<String, List<String>> headerArrayToMap(final Header[] headers) {
    final Map<String, List<String>> headersMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (final Header header : headers) {
      if (!skipHeader(header)) {
        headersMap.put(
            header.getName(),
            headersMap.getOrDefault(
                header.getName(), new LinkedList<>(Collections.singletonList(header.getValue()))));
      }
    }
    return headersMap;
  }

  /**
   * @param header header line to check
   * @return true if the given header should be excluded when signing
   */
  private static boolean skipHeader(final Header header) {
    return ("content-length".equalsIgnoreCase(header.getName())
            && "0".equals(header.getValue())) // Strip Content-Length: 0
        || "host".equalsIgnoreCase(header.getName()); // Host comes from endpoint
  }

  /**
   * @param mapHeaders Map of header entries
   * @return modelled Header objects
   */
  private static Header[] mapToHeaderArray(final Map<String, List<String>> mapHeaders) {
    final Header[] headers = new Header[mapHeaders.size()];
    int i = 0;
    for (final Map.Entry<String, List<String>> headerEntry : mapHeaders.entrySet()) {
      for (final String value : headerEntry.getValue()) {
        headers[i++] = new BasicHeader(headerEntry.getKey(), value);
      }
    }
    return headers;
  }
}
