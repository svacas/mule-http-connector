/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.http.internal.request;

import static org.mule.tck.junit4.matcher.IsEmptyOptional.empty;
import static org.mule.test.http.AllureConstants.HttpFeature.HTTP_EXTENSION;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mule.extension.http.internal.request.HttpRequesterConnectionManager;
import org.mule.extension.http.internal.request.HttpRequesterConnectionManager.ShareableHttpClient;
import org.mule.runtime.http.api.HttpService;
import org.mule.runtime.http.api.client.HttpClient;
import org.mule.runtime.http.api.client.HttpClientConfiguration;
import org.mule.runtime.http.api.client.HttpClientFactory;
import org.mule.tck.junit4.AbstractMuleTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mockito;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;

@Feature(HTTP_EXTENSION)
@Story("HTTP Request")
public class HttpRequesterConnectionManagerTestCase extends AbstractMuleTestCase {

  private static final String CONFIG_NAME = "config";

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private HttpService httpService = mock(HttpService.class);
  private HttpClient delegateHttpClient = spy(HttpClient.class);
  private HttpClient otherHttpClient = spy(HttpClient.class);
  private HttpRequesterConnectionManager connectionManager = new HttpRequesterConnectionManager(httpService);

  @Before
  public void setUp() {
    HttpClientFactory httpClientFactory = mock(HttpClientFactory.class);
    when(httpService.getClientFactory()).thenReturn(httpClientFactory);
    when(httpClientFactory.create(any())).thenAnswer(
                                                     invocation -> {
                                                       HttpClientConfiguration configuration =
                                                           (HttpClientConfiguration) invocation.getArguments()[0];
                                                       if (CONFIG_NAME.equals(configuration.getName())) {
                                                         return delegateHttpClient;
                                                       } else {
                                                         return otherHttpClient;
                                                       }
                                                     });
  }

  @Test
  public void lookup() {
    assertThat(connectionManager.lookup(CONFIG_NAME), is(empty()));
    ShareableHttpClient client = connectionManager.create(CONFIG_NAME, mock(HttpClientConfiguration.class));
    assertThat(connectionManager.lookup(CONFIG_NAME).get(), is(sameInstance(client)));
  }

  @Test
  public void creatingAnExistingClientFails() {
    connectionManager.create(CONFIG_NAME, mock(HttpClientConfiguration.class));
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("There's an HttpClient available for config already.");
    connectionManager.create(CONFIG_NAME, mock(HttpClientConfiguration.class));
  }

  @Test
  public void sharedClientIsStartedByFirstUse() {
    HttpClientConfiguration configuration = getHttpClientConfiguration(CONFIG_NAME);
    ShareableHttpClient client1 = connectionManager.create(CONFIG_NAME, configuration);
    ShareableHttpClient client2 = connectionManager.lookup(CONFIG_NAME).get();
    client1.start();
    verify(delegateHttpClient).start();
    reset(delegateHttpClient);
    client2.start();
    verify(delegateHttpClient, never()).start();
  }

  @Test
  public void sharedClientIsStoppedByLastUse() {
    ShareableHttpClient client1 = connectionManager.create(CONFIG_NAME, getHttpClientConfiguration(CONFIG_NAME));
    ShareableHttpClient client2 = connectionManager.lookup(CONFIG_NAME).get();
    client1.start();
    client2.start();
    client1.stop();
    verify(delegateHttpClient, never()).stop();
    reset(delegateHttpClient);
    client2.stop();
    verify(delegateHttpClient).stop();
  }

  @Test
  public void differentClientsDoNotAffectEachOther() {
    ShareableHttpClient client1 = connectionManager.create(CONFIG_NAME, getHttpClientConfiguration(CONFIG_NAME));
    String otherConfig = "otherConfig";
    connectionManager.create(otherConfig, getHttpClientConfiguration(otherConfig));
    client1.start();
    verify(otherHttpClient, never()).start();
    client1.stop();
    verify(otherHttpClient, never()).stop();
  }

  @Test
  public void clientIsStartedAfterFirstError() {
    doThrow(RuntimeException.class).doNothing().when(delegateHttpClient).start();
    ShareableHttpClient client = connectionManager.create(CONFIG_NAME, getHttpClientConfiguration(CONFIG_NAME));
    try {
      client.start();
    } catch (Exception e) {
      // Ignore first exception
    }
    client.start();
    verify(delegateHttpClient, Mockito.times(2)).start();
  }

  private HttpClientConfiguration getHttpClientConfiguration(String configName) {
    HttpClientConfiguration configuration = mock(HttpClientConfiguration.class);
    when(configuration.getName()).thenReturn(configName);
    return configuration;
  }

}
