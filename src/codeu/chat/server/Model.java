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

import java.util.Comparator;

import codeu.chat.codeU_db.DataBaseConnection;
import codeu.chat.common.*;
import codeu.chat.util.Logger;
import codeu.chat.util.Time;
import codeu.chat.util.Uuid;
import codeu.chat.util.store.Store;
import codeu.chat.util.store.StoreAccessor;

public final class Model {

  private static final Comparator<Uuid> UUID_COMPARE = new Comparator<Uuid>() {

    @Override
    public int compare(Uuid a, Uuid b) {

      if (a == b) { return 0; }

      if (a == null && b != null) { return -1; }

      if (a != null && b == null) { return 1; }

      final int order = Integer.compare(a.id(), b.id());
      return order == 0 ? compare(a.root(), b.root()) : order;
    }
  };

  private static final Comparator<Time> TIME_COMPARE = new Comparator<Time>() {
    @Override
    public int compare(Time a, Time b) {
      return a.compareTo(b);
    }
  };

  private static final Comparator<String> STRING_COMPARE = String.CASE_INSENSITIVE_ORDER;

  private final static Logger.Log LOG = Logger.newLog(Model.class);

  private final Store<Uuid, User> userById = new Store<>(UUID_COMPARE);
  private final Store<Time, User> userByTime = new Store<>(TIME_COMPARE);
  private final Store<String, User> userByText = new Store<>(STRING_COMPARE);

  private final Store<Uuid, Conversation> conversationById = new Store<>(UUID_COMPARE);
  private final Store<Time, Conversation> conversationByTime = new Store<>(TIME_COMPARE);
  private final Store<String, Conversation> conversationByText = new Store<>(STRING_COMPARE);

  private final Store<Uuid, Message> messageById = new Store<>(UUID_COMPARE);
  private final Store<Time, Message> messageByTime = new Store<>(TIME_COMPARE);
  private final Store<String, Message> messageByText = new Store<>(STRING_COMPARE);

  private final Uuid.Generator userGenerations = new LinearUuidGenerator(null, 1, Integer.MAX_VALUE);
  private Uuid currentUserGeneration = userGenerations.make();

  public void add(User user) {

    currentUserGeneration = userGenerations.make();

    try {
      user = new User(user.id, user.name, user.creation, user.password);
      DataBaseConnection.dbUpdate("INSERT INTO USERS (ID,UNAME,TIMECREATED,PASSWORD) " +
              "VALUES (" + SQLFormatter.sqlID(user.id) + ", " + SQLFormatter.sqlName(user.name) + ", " +
              SQLFormatter.sqlCreationTime(user.creation) + ", " + SQLFormatter.sqlPassword(user.password) + ");");
      LOG.info(
              "newUser success (user.id=%s user.name=%s user.time=%s)",
              user.id,
              user.name,
              user.creation);
    } catch (Exception e) {
      LOG.info(
              "newUser fail - Database insertion error (user.id=%s user.name=%s user.time=%s)",
              user.id,
              user.name,
              user.creation);
      System.err.println(e.getClass().getName() + ": " + e.getMessage());
      System.exit(0);
    }
  }

  public StoreAccessor<Uuid, User> userById() {
    return userById;
  }

  public StoreAccessor<Time, User> userByTime() {
    return userByTime;
  }

  public StoreAccessor<String, User> userByText() {
    return userByText;
  }

  public Uuid userGeneration() {
    return currentUserGeneration;
  }

  public void add(Conversation conversation) {

    try {
      DataBaseConnection.dbUpdate("INSERT INTO CONVERSATIONS (ID,CNAME,OWNERID,TimeCreated) " +
              "VALUES (" + SQLFormatter.sqlID(conversation.id) + ", " + SQLFormatter.sqlName(conversation.title) + ", " +
              SQLFormatter.sqlID(conversation.owner) + ", " + SQLFormatter.sqlCreationTime(conversation.creation) + ");");

      LOG.info("Conversation added: " + conversation.id);
    } catch (Exception e) {
      LOG.info(
              "newConversation fail - Verify connection and try again shortly");
      System.err.println(e.getClass().getName() + ": " + e.getMessage());
      System.exit(0);
    }

    try {

      DataBaseConnection.dbUpdate("INSERT INTO USER_CONVERSATION (ID,USERID,CONVERSATIONID) " +
              "VALUES (" + SQLFormatter.sqlID(conversation.id, conversation.owner) + ", " + SQLFormatter.sqlID(conversation.owner) + ", " + SQLFormatter.sqlID(conversation.id) + ");");

      LOG.info("User " + conversation.owner + " added to: " + conversation.id);

    } catch (Exception e) {
      LOG.info(
              "newConversation fail - Verify connection and try again shortly");
      System.err.println(e.getClass().getName() + ": " + e.getMessage());
      System.exit(0);
    }
  }

  public StoreAccessor<Uuid, Conversation> conversationById() {
    return conversationById;
  }

  public StoreAccessor<Time, Conversation> conversationByTime() {
    return conversationByTime;
  }

  public StoreAccessor<String, Conversation> conversationByText() {
    return conversationByText;
  }

  public void add(Message message) {
    messageById.insert(message.id, message);
    messageByTime.insert(message.creation, message);
    messageByText.insert(message.content, message);
  }

  public StoreAccessor<Uuid, Message> messageById() {
    return messageById;
  }

  public StoreAccessor<Time, Message> messageByTime() {
    return messageByTime;
  }

  public StoreAccessor<String, Message> messageByText() {
    return messageByText;
  }
}
