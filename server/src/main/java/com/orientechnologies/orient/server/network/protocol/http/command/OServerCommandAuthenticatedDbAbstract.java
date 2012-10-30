/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.server.network.protocol.http.command;

import java.io.IOException;
import java.util.List;

import com.orientechnologies.common.concur.lock.OLockException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.exception.ODatabaseException;
import com.orientechnologies.orient.core.exception.OSecurityAccessException;
import com.orientechnologies.orient.core.serialization.serializer.OStringSerializerHelper;
import com.orientechnologies.orient.server.db.OSharedDocumentDatabase;
import com.orientechnologies.orient.server.network.protocol.http.OHttpRequest;
import com.orientechnologies.orient.server.network.protocol.http.OHttpRequestException;
import com.orientechnologies.orient.server.network.protocol.http.OHttpResponse;
import com.orientechnologies.orient.server.network.protocol.http.OHttpSession;
import com.orientechnologies.orient.server.network.protocol.http.OHttpSessionManager;
import com.orientechnologies.orient.server.network.protocol.http.OHttpUtils;

/**
 * Database based authenticated command. Authenticats against the database taken as second parameter of the URL. The URL must be in
 * this format:
 * 
 * <pre>
 * <command>/<database>[/...]
 * </pre>
 * 
 * @author Luca Garulli
 * 
 */
public abstract class OServerCommandAuthenticatedDbAbstract extends OServerCommandAbstract {

  public static final char   DBNAME_DIR_SEPARATOR   = '$';
  public static final String SESSIONID_UNAUTHORIZED = "-";
  public static final String SESSIONID_LOGOUT       = "!";

  @Override
  public boolean beforeExecute(final OHttpRequest iRequest, OHttpResponse iResponse) throws IOException {
    final String[] urlParts = iRequest.url.substring(1).split("/");
    if (urlParts.length < 2)
      throw new OHttpRequestException("Syntax error in URL. Expected is: <command>/<database>[/...]");

    iRequest.databaseName = urlParts[1].replace(DBNAME_DIR_SEPARATOR, '/');
    final List<String> authenticationParts = iRequest.authorization != null ? OStringSerializerHelper.split(iRequest.authorization,
        ':') : null;

    final OHttpSession currentSession;
    if (iRequest.sessionId != null && iRequest.sessionId.length() > 1)
      currentSession = OHttpSessionManager.getInstance().getSession(iRequest.sessionId);
    else
      currentSession = null;

    if (currentSession == null) {
      // NO SESSION
      if (iRequest.authorization == null || SESSIONID_LOGOUT.equals(iRequest.sessionId)) {
        sendAuthorizationRequest(iRequest, iResponse, iRequest.databaseName);
        return false;
      } else
        return authenticate(iRequest, iResponse, authenticationParts, iRequest.databaseName);

    } else {
      // CHECK THE SESSION VALIDITY
      if (!currentSession.getDatabaseName().equals(iRequest.databaseName)) {

        // SECURITY PROBLEM: CROSS DATABASE REQUEST!
        OLogManager.instance().warn(this,
            "Session %s is trying to access to the database '%s', but has been authenticated against the database '%s'",
            iRequest.sessionId, iRequest.databaseName, currentSession.getDatabaseName());
        sendAuthorizationRequest(iRequest, iResponse, iRequest.databaseName);
        return false;

      } else if (authenticationParts != null && !currentSession.getUserName().equals(authenticationParts.get(0))) {

        // SECURITY PROBLEM: CROSS DATABASE REQUEST!
        OLogManager.instance().warn(this,
            "Session %s is trying to access to the database '%s' with user '%s', but has been authenticated with user '%s'",
            iRequest.sessionId, iRequest.databaseName, authenticationParts.get(0), currentSession.getUserName());
        sendAuthorizationRequest(iRequest, iResponse, iRequest.databaseName);
        return false;
      }

      return true;
    }
  }

  protected boolean authenticate(final OHttpRequest iRequest, final OHttpResponse iResponse,
      final List<String> iAuthenticationParts, final String iDatabaseName) throws IOException {
    ODatabaseDocumentTx db = null;
    try {
      db = OSharedDocumentDatabase.acquire(iDatabaseName, iAuthenticationParts.get(0), iAuthenticationParts.get(1));

      // AUTHENTICATED: CREATE THE SESSION
      iRequest.sessionId = OHttpSessionManager.getInstance().createSession(iDatabaseName, iAuthenticationParts.get(0));
      iResponse.sessionId = iRequest.sessionId;
      return true;

    } catch (OSecurityAccessException e) {
      // WRONG USER/PASSWD
    } catch (OLockException e) {
      OLogManager.instance().error(this, "Cannot access to the database '" + iDatabaseName + "'", ODatabaseException.class, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      OLogManager.instance().error(this, "Cannot access to the database '" + iDatabaseName + "'", ODatabaseException.class, e);
    } finally {
      if (db != null)
        OSharedDocumentDatabase.release(db);
      else
        // WRONG USER/PASSWD
        sendAuthorizationRequest(iRequest, iResponse, iDatabaseName);
    }
    return false;
  }

  protected void sendAuthorizationRequest(final OHttpRequest iRequest, final OHttpResponse iResponse, final String iDatabaseName)
      throws IOException {
    // UNAUTHORIZED
    iRequest.sessionId = SESSIONID_UNAUTHORIZED;
    String header = null;
    if (iRequest.authentication == null || iRequest.authentication.equalsIgnoreCase("basic")) {
      header = "WWW-Authenticate: Basic realm=\"OrientDB db-" + iDatabaseName + "\"";
    }
    iResponse.send(OHttpUtils.STATUS_AUTH_CODE, OHttpUtils.STATUS_AUTH_DESCRIPTION, OHttpUtils.CONTENT_TEXT_PLAIN,
        "401 Unauthorized.", header, false);
  }

  protected ODatabaseDocumentTx getProfiledDatabaseInstance(final OHttpRequest iRequest) throws InterruptedException {
    if (iRequest.authorization == null)
      throw new OSecurityAccessException(iRequest.databaseName, "No user and password received");

    final List<String> parts = OStringSerializerHelper.split(iRequest.authorization, ':');

    final ODatabaseDocumentTx db = OSharedDocumentDatabase.acquire(iRequest.databaseName, parts.get(0), parts.get(1));
    if (db != null) {
      iRequest.data.lastDatabase = db.getName();
      iRequest.data.lastUser = db.getUser() != null ? db.getUser().getName() : null;
    } else {
      iRequest.data.lastDatabase = null;
      iRequest.data.lastUser = null;
    }

    return db;
  }
}
