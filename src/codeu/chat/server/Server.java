// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package codeu.chat.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import codeu.chat.common.*;
import codeu.chat.util.*;
import codeu.chat.util.connections.Connection;
import codeu.chat.server.user_recommendation.K_Means;

public final class Server {

  private static final Logger.Log LOG = Logger.newLog(Server.class);

  private static final int RELAY_REFRESH_MS = 5000;  // 5 seconds

  private final Timeline timeline = new Timeline();

  private final Uuid id;
  private final byte[] secret;

  private final Model model = new Model();
  private final View view = new View(model);
  private final Controller controller;

  private final K_Means k_means;

  private final Relay relay;
  private Uuid lastSeen = Uuid.NULL;

  public Server(final Uuid id, final byte[] secret, final Relay relay) {

    this.id = id;
    this.secret = Arrays.copyOf(secret, secret.length);

    this.controller = new Controller(id, model);
    this.relay = relay;

    this.k_means = new K_Means(model);

    timeline.scheduleNow(new Runnable() {
      @Override
      public void run() {
        try {

          LOG.info("Reading update from relay...");

          for (final Relay.Bundle bundle : relay.read(id, secret, lastSeen, 32)) {
            onBundle(bundle);
            lastSeen = bundle.id();
          }

        } catch (Exception ex) {

          LOG.error(ex, "Failed to read update from relay.");

        }

        timeline.scheduleIn(RELAY_REFRESH_MS, this);
      }
    });
  }

  public void handleConnection(final Connection connection) {
    timeline.scheduleNow(new Runnable() {
      @Override
      public void run() {
        try {

          LOG.info("Handling connection...");

          final boolean success = onMessage(
              connection.in(),
              connection.out());

          LOG.info("Connection handled: %s", success ? "ACCEPTED" : "REJECTED");
        } catch (Exception ex) {

          LOG.error(ex, "Exception while handling connection.");

        }

        try {
          connection.close();
        } catch (Exception ex) {
          LOG.error(ex, "Exception while closing connection.");
        }
      }
    });
  }

  private boolean onMessage(InputStream in, OutputStream out) throws IOException {

    final int type = Serializers.INTEGER.read(in);

    if (type == NetworkCode.NEW_MESSAGE_REQUEST) {

      final Uuid author = Uuid.SERIALIZER.read(in);
      final Uuid conversation = Uuid.SERIALIZER.read(in);
      final String content = Serializers.STRING.read(in);

      final Message message = controller.newMessage(author, conversation, content);

      Serializers.INTEGER.write(out, NetworkCode.NEW_MESSAGE_RESPONSE);
      Serializers.nullable(Message.SERIALIZER).write(out, message);

      timeline.scheduleNow(createSendToRelayEvent(
          author,
          conversation,
          message.id));

    } else if (type == NetworkCode.NEW_USER_REQUEST) {

      final User issuer = User.SERIALIZER.read(in);
      final String name = Serializers.STRING.read(in);
      final String password = Serializers.STRING.read(in);

      final User user = controller.newUser(issuer, name, password);

      Serializers.INTEGER.write(out, NetworkCode.NEW_USER_RESPONSE);
      Serializers.nullable(User.SERIALIZER).write(out, user);

    } else if (type == NetworkCode.NEW_CONVERSATION_REQUEST) {

      final String title = Serializers.STRING.read(in);
      final Uuid owner = Uuid.SERIALIZER.read(in);

      final Conversation conversation = controller.newConversation(title, owner);

      Serializers.INTEGER.write(out, NetworkCode.NEW_CONVERSATION_RESPONSE);
      Serializers.nullable(Conversation.SERIALIZER).write(out, conversation);

    } else if (type == NetworkCode.ADD_USER_TO_CONVERSATION_REQUEST) {

      final Uuid issuerID = Uuid.SERIALIZER.read(in);
      final Uuid userID = Uuid.SERIALIZER.read(in);
      final Uuid conversationID = Uuid.SERIALIZER.read(in);

      boolean response = false;

      response = controller.addUserToConversation(issuerID, userID, conversationID);

      Serializers.INTEGER.write(out, NetworkCode.ADD_USER_TO_CONVERSATION_RESPONSE);
      Serializers.BOOLEAN.write(out, response);

    } else if (type == NetworkCode.GET_USERS_BY_ID_REQUEST) {

      final Collection<Uuid> ids = Serializers.collection(Uuid.SERIALIZER).read(in);

      final Collection<User> users = view.getUsers(ids);

      Serializers.INTEGER.write(out, NetworkCode.GET_USERS_BY_ID_RESPONSE);
      Serializers.collection(User.SERIALIZER).write(out, users);

    } else if (type == NetworkCode.GET_ALL_CONVERSATIONS_REQUEST) {
      Uuid userID = Uuid.SERIALIZER.read(in);

      final Collection<ConversationSummary> conversations = view.getAllConversations(userID);

      Serializers.INTEGER.write(out, NetworkCode.GET_ALL_CONVERSATIONS_RESPONSE);
      Serializers.collection(ConversationSummary.SERIALIZER).write(out, conversations);

    } else if (type == NetworkCode.GET_CONVERSATIONS_BY_ID_REQUEST) {

      final Collection<Uuid> ids = Serializers.collection(Uuid.SERIALIZER).read(in);

      final Collection<Conversation> conversations = view.getConversations(ids);

      Serializers.INTEGER.write(out, NetworkCode.GET_CONVERSATIONS_BY_ID_RESPONSE);
      Serializers.collection(Conversation.SERIALIZER).write(out, conversations);

    } else if (type == NetworkCode.GET_MESSAGES_BY_ID_REQUEST) {

      final Collection<Uuid> ids = Serializers.collection(Uuid.SERIALIZER).read(in);

      final Collection<Message> messages = view.getMessages(ids);

      Serializers.INTEGER.write(out, NetworkCode.GET_MESSAGES_BY_ID_RESPONSE);
      Serializers.collection(Message.SERIALIZER).write(out, messages);

    } else if (type == NetworkCode.GET_USER_GENERATION_REQUEST) {

      Serializers.INTEGER.write(out, NetworkCode.GET_USER_GENERATION_RESPONSE);
      Uuid.SERIALIZER.write(out, view.getUserGeneration());

    } else if (type == NetworkCode.GET_USERS_EXCLUDING_REQUEST) {

      final Collection<Uuid> ids = Serializers.collection(Uuid.SERIALIZER).read(in);

      final Collection<User> users = view.getUsersExcluding(ids);

      Serializers.INTEGER.write(out, NetworkCode.GET_USERS_EXCLUDING_RESPONSE);
      Serializers.collection(User.SERIALIZER).write(out, users);

    } else if (type == NetworkCode.GET_CONVERSATIONS_BY_TIME_REQUEST) {

      final Time startTime = Time.SERIALIZER.read(in);
      final Time endTime = Time.SERIALIZER.read(in);

      final Collection<Conversation> conversations = view.getConversations(startTime, endTime);

      Serializers.INTEGER.write(out, NetworkCode.GET_CONVERSATIONS_BY_TIME_RESPONSE);
      Serializers.collection(Conversation.SERIALIZER).write(out, conversations);

    } else if (type == NetworkCode.GET_CONVERSATIONS_BY_TITLE_REQUEST) {

      final String filter = Serializers.STRING.read(in);

      final Collection<Conversation> conversations = view.getConversations(filter);

      Serializers.INTEGER.write(out, NetworkCode.GET_CONVERSATIONS_BY_TITLE_RESPONSE);
      Serializers.collection(Conversation.SERIALIZER).write(out, conversations);

    } else if (type == NetworkCode.GET_MESSAGES_BY_TIME_REQUEST) {

      final Uuid conversation = Uuid.SERIALIZER.read(in);
      final Time startTime = Time.SERIALIZER.read(in);
      final Time endTime = Time.SERIALIZER.read(in);

      final Collection<Message> messages = view.getMessages(conversation, startTime, endTime);

      Serializers.INTEGER.write(out, NetworkCode.GET_MESSAGES_BY_TIME_RESPONSE);
      Serializers.collection(Message.SERIALIZER).write(out, messages);

    } else if (type == NetworkCode.GET_MESSAGES_BY_RANGE_REQUEST) {

      final Uuid rootMessage = Uuid.SERIALIZER.read(in);
      final int range = Serializers.INTEGER.read(in);

      final Collection<Message> messages = view.getMessages(rootMessage, range);

      Serializers.INTEGER.write(out, NetworkCode.GET_MESSAGES_BY_RANGE_RESPONSE);
      Serializers.collection(Message.SERIALIZER).write(out, messages);

    } else if(type == NetworkCode.GENERATE_USER_CLUSTERS_REQUEST) {

      final int iterations = Serializers.INTEGER.read(in);
      final Uuid userID = Uuid.SERIALIZER.read(in);

      boolean success = k_means.runClusterer(iterations, userID);

      Serializers.INTEGER.write(out, NetworkCode.GENERATE_USER_CLUSTERS_RESPONSE);
      Serializers.BOOLEAN.write(out, success);

    } else if(type == NetworkCode.GET_RECOMMENDED_USERS_REQUEST) {

      final Uuid user = Uuid.SERIALIZER.read(in);

      Collection<User> recommendedUsers = view.getRecommendedUsers(user);

      Serializers.INTEGER.write(out, NetworkCode.GET_RECOMMENDED_USERS_RESPONSE);
      Serializers.collection(User.SERIALIZER).write(out, recommendedUsers);

    } else if(type == NetworkCode.CHECK_EXISTENT_USERNAME_REQUEST) {

      final String name = Serializers.STRING.read(in);

      boolean isExistent = view.isUserTaken(name);

      Serializers.INTEGER.write(out, NetworkCode.CHECK_EXISTENT_USERNAME_RESPONSE);
      Serializers.BOOLEAN.write(out, isExistent);

    }else {

      // In the case that the message was not handled make a dummy message with
      // the type "NO_MESSAGE" so that the client still gets something.

      Serializers.INTEGER.write(out, NetworkCode.NO_MESSAGE);

    }

    return true;
  }

  private void onBundle(Relay.Bundle bundle) {

    final Relay.Bundle.Component relayUser = bundle.user();
    final Relay.Bundle.Component relayConversation = bundle.conversation();
    final Relay.Bundle.Component relayMessage = bundle.user();

    String password = "Temporal Password for Relay";

    User user = model.getSingleUser(relayUser.id());

    if (user == null) {
      user = controller.newUser(relayUser.id(), relayUser.text(), relayUser.time(), password);
    }

    Conversation conversation = model.getSingleConversation(relayConversation.id());

    if (conversation == null) {

      // As the relay does not tell us who made the conversation - the first person who
      // has a message in the conversation will get ownership over this server's copy
      // of the conversation.
      conversation = controller.newConversation(relayConversation.id(),
          relayConversation.text(),
          user.id,
          relayConversation.time());
    }

    Message message = model.getSingleMessage(relayMessage.id());

    if (message == null) {
      message = controller.newMessage(relayMessage.id(),
          user.id,
          conversation.id,
          relayMessage.text(),
          relayMessage.time());
    }
  }

  private Runnable createSendToRelayEvent(final Uuid userId,
                                          final Uuid conversationId,
                                          final Uuid messageId) {
    return new Runnable() {
      @Override
      public void run() {
        final User user = view.findUser(userId);
        final Conversation conversation = view.findConversation(conversationId);
        final Message message = view.findMessage(messageId);
        relay.write(id,
            secret,
            relay.pack(user.id, user.name, user.creation),
            relay.pack(conversation.id, conversation.title, conversation.creation),
            relay.pack(message.id, message.content, message.creation));
      }
    };
  }
}
