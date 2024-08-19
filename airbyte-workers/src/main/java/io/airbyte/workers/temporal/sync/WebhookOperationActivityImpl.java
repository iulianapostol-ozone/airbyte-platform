/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.sync;

import static io.airbyte.metrics.lib.ApmTraceConstants.ACTIVITY_TRACE_OPERATION_NAME;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.WEBHOOK_CONFIG_ID_KEY;

import com.fasterxml.jackson.databind.JsonNode;
import datadog.trace.api.Trace;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import io.airbyte.api.client.AirbyteApiClient;
import io.airbyte.api.client.model.generated.ScopeType;
import io.airbyte.api.client.model.generated.SecretPersistenceConfig;
import io.airbyte.api.client.model.generated.SecretPersistenceConfigGetRequestBody;
import io.airbyte.commons.json.Jsons;
import io.airbyte.config.OperatorWebhookInput;
import io.airbyte.config.WebhookConfig;
import io.airbyte.config.WebhookOperationConfigs;
import io.airbyte.config.secrets.SecretsRepositoryReader;
import io.airbyte.config.secrets.persistence.RuntimeSecretPersistence;
import io.airbyte.featureflag.FeatureFlagClient;
import io.airbyte.featureflag.Organization;
import io.airbyte.featureflag.UseRuntimeSecretPersistence;
import io.airbyte.metrics.lib.ApmTraceUtils;
import io.airbyte.metrics.lib.MetricClientFactory;
import io.airbyte.metrics.lib.OssMetricsRegistry;
import io.airbyte.workers.helper.SecretPersistenceConfigHelper;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Webhook operation activity temporal impl.
 */
@Singleton
public class WebhookOperationActivityImpl implements WebhookOperationActivity {

  private static final Logger LOGGER = LoggerFactory.getLogger(WebhookOperationActivityImpl.class);
  private static final int MAX_RETRIES = 3;

  private static final RetryPolicy<Object> WEBHOOK_RETRY_POLICY = RetryPolicy.builder()
      .withBackoff(1, 5, ChronoUnit.SECONDS)
      .withMaxRetries(MAX_RETRIES)
      .handle(Exception.class)
      .build();

  private final HttpClient httpClient;
  private final SecretsRepositoryReader secretsRepositoryReader;
  private final AirbyteApiClient airbyteApiClient;
  private final FeatureFlagClient featureFlagClient;

  public WebhookOperationActivityImpl(@Named("webhookHttpClient") final HttpClient httpClient,
                                      final SecretsRepositoryReader secretsRepositoryReader,
                                      final AirbyteApiClient airbyteApiClient,
                                      final FeatureFlagClient featureFlagClient) {
    this.httpClient = httpClient;
    this.secretsRepositoryReader = secretsRepositoryReader;
    this.airbyteApiClient = airbyteApiClient;
    this.featureFlagClient = featureFlagClient;
  }

  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  @Override
  public boolean invokeWebhook(final OperatorWebhookInput input) {
    MetricClientFactory.getMetricClient().count(OssMetricsRegistry.ACTIVITY_WEBHOOK_OPERATION, 1);

    LOGGER.debug("Webhook operation input: {}", input);
    LOGGER.debug("Found webhook config: {}", input.getWorkspaceWebhookConfigs());

    final JsonNode fullWebhookConfigJson;
    final UUID organizationId = input.getConnectionContext().getOrganizationId();
    if (organizationId != null && featureFlagClient.boolVariation(UseRuntimeSecretPersistence.INSTANCE, new Organization(organizationId))) {
      try {
        final SecretPersistenceConfig secretPersistenceConfig = airbyteApiClient.getSecretPersistenceConfigApi().getSecretsPersistenceConfig(
            new SecretPersistenceConfigGetRequestBody(ScopeType.ORGANIZATION, organizationId));
        final RuntimeSecretPersistence runtimeSecretPersistence =
            SecretPersistenceConfigHelper.fromApiSecretPersistenceConfig(secretPersistenceConfig);
        fullWebhookConfigJson =
            secretsRepositoryReader.hydrateConfigFromRuntimeSecretPersistence(input.getWorkspaceWebhookConfigs(), runtimeSecretPersistence);
      } catch (final IOException e) {
        throw new RuntimeException(e);
      }
    } else {
      fullWebhookConfigJson = secretsRepositoryReader.hydrateConfigFromDefaultSecretPersistence(input.getWorkspaceWebhookConfigs());
    }
    final WebhookOperationConfigs webhookConfigs = Jsons.object(fullWebhookConfigJson, WebhookOperationConfigs.class);
    final Optional<WebhookConfig> webhookConfig =
        webhookConfigs.getWebhookConfigs().stream().filter((config) -> config.getId().equals(input.getWebhookConfigId())).findFirst();
    if (webhookConfig.isEmpty()) {
      throw new RuntimeException(String.format("Cannot find webhook config %s", input.getWebhookConfigId().toString()));
    }

    ApmTraceUtils.addTagsToTrace(Map.of(WEBHOOK_CONFIG_ID_KEY, input.getWebhookConfigId()));
    LOGGER.info("Invoking webhook operation {}", webhookConfig.get().getName());

    final HttpRequest.Builder requestBuilder = buildRequest(input, webhookConfig);

    return Failsafe.with(WEBHOOK_RETRY_POLICY).get(() -> sendWebhook(requestBuilder, webhookConfig));
  }

  private HttpRequest.Builder buildRequest(final OperatorWebhookInput input, final Optional<WebhookConfig> webhookConfig) {
    final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
        .uri(URI.create(input.getExecutionUrl()));
    if (input.getExecutionBody() != null) {
      requestBuilder.POST(HttpRequest.BodyPublishers.ofString(input.getExecutionBody()));
    }
    if (webhookConfig.get().getAuthToken() != null) {
      requestBuilder
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + webhookConfig.get().getAuthToken()).build();
    }

    return requestBuilder;
  }

  private boolean sendWebhook(final HttpRequest.Builder requestBuilder, final Optional<WebhookConfig> webhookConfig)
      throws IOException, InterruptedException {
    final HttpResponse<String> response = this.httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

    final boolean isSuccessful = response != null && response.statusCode() >= 200 && response.statusCode() <= 300;
    LOGGER.info("Webhook {} execution status {}", webhookConfig.get().getName(), isSuccessful ? "successful" : "failed");
    if (!isSuccessful) {
      LOGGER.error("Webhook {} error code: {} response: {}", webhookConfig.get().getName(), response.statusCode(), response.body());
    }
    return isSuccessful;
  }

}
