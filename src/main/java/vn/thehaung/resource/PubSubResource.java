package vn.thehaung.resource;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.api.gax.core.CredentialsProvider;
import com.google.cloud.pubsub.v1.*;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

/**
 * @author : Hau Nguyen
 * @from : 8/7/22
 **/

@Path("/pubsub")
public class PubSubResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(PubSubResource.class);

    @ConfigProperty(name = "quarkus.google.cloud.project-id")
    String projectId;// Inject the projectId property from application.properties

    private TopicName topicName;
    private Subscriber subscriber;

    @Inject
    CredentialsProvider credentialsProvider;

    @PostConstruct
    void init() throws IOException {
        // Init topic and subscription, the topic must have been created before
        topicName = TopicName.of(projectId, "test-topic");
        ProjectSubscriptionName subscriptionName = initSubscription();

        // Subscribe to PubSub
        MessageReceiver receiver = (message, consumer) -> {
            LOGGER.info("Got message {}", message.getData().toStringUtf8());
            consumer.ack();
        };
        subscriber = Subscriber.newBuilder(subscriptionName, receiver).build();
        subscriber.startAsync().awaitRunning();
    }

    @PreDestroy
    void destroy() {
        // Stop the subscription at destroy time
        if (subscriber != null) {
            subscriber.stopAsync();
        }
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String pubsub() throws IOException, InterruptedException {
        // Init a publisher to the topic
        Publisher publisher = Publisher.newBuilder(topicName)
                .setCredentialsProvider(credentialsProvider)
                .build();
        try {
            ByteString data = ByteString.copyFromUtf8("my-message");// Create a new message
            PubsubMessage pubsubMessage = PubsubMessage.newBuilder().setData(data).build();
            ApiFuture<String> messageIdFuture = publisher.publish(pubsubMessage);// Publish the message
            ApiFutures.addCallback(messageIdFuture, new ApiFutureCallback<>() {// Wait for message submission and log the result
                public void onSuccess(String messageId) {
                    LOGGER.info("published with message id {}", messageId);
                }

                public void onFailure(Throwable t) {
                    LOGGER.warn("failed to publish: {}", t.getCause());
                }
            }, MoreExecutors.directExecutor());
        } finally {
            publisher.shutdown();
            publisher.awaitTermination(1, TimeUnit.MINUTES);
        }
        return "";
    }

    private ProjectSubscriptionName initSubscription() throws IOException {
        // List all existing subscriptions and create the 'test-subscription' if needed
        ProjectSubscriptionName subscriptionName = ProjectSubscriptionName.of(projectId, "test-subscription");
        SubscriptionAdminSettings subscriptionAdminSettings = SubscriptionAdminSettings.newBuilder()
                .setCredentialsProvider(credentialsProvider)
                .build();
        try (SubscriptionAdminClient subscriptionAdminClient = SubscriptionAdminClient.create(subscriptionAdminSettings)) {
            Iterable<Subscription> subscriptions = subscriptionAdminClient.listSubscriptions(ProjectName.of(projectId))
                    .iterateAll();
            Optional<Subscription> existing = StreamSupport.stream(subscriptions.spliterator(), false)
                    .filter(sub -> sub.getName().equals(subscriptionName.toString()))
                    .findFirst();
            if (existing.isEmpty()) {
                subscriptionAdminClient.createSubscription(subscriptionName, topicName, PushConfig.getDefaultInstance(), 0);
            }
        }
        return subscriptionName;
    }
}
