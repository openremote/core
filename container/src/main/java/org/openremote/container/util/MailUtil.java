/*
 * Copyright 2023, OpenRemote Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openremote.container.util;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import org.openremote.model.mail.MailMessage;

public class MailUtil {

  public static class MessageContent {

    protected List<Header> headers;
    protected String mimeType;
    protected String content;

    protected MessageContent(String mimeType, String content, List<Header> headers) {
      this.mimeType = mimeType;
      this.content = content;
      this.headers = headers;
    }
  }

  protected MailUtil() {}

  public static MailMessage toMailMessage(Message message, boolean preferHTML)
      throws MessagingException, IOException {
    MessageContent messageContent = getMessageContent(message, new ArrayList<>(), true, preferHTML);
    if (messageContent == null) {
      return null;
    }

    List<Header> headers = messageContent.headers;
    Map<String, List<String>> headerStrings =
        headers.stream()
            .collect(
                Collectors.groupingBy(
                    Header::getName, Collectors.mapping(Header::getValue, Collectors.toList())));

    return new MailMessage(
        messageContent.content,
        messageContent.mimeType,
        headerStrings,
        message.getSubject(),
        message.getSentDate(),
        Arrays.stream(message.getFrom())
            .map(a -> ((InternetAddress) a).getAddress())
            .toArray(String[]::new));
  }

  protected static MessageContent getMessageContent(
      Part p, List<Header> headers, boolean isTopLevel, boolean preferHTML)
      throws MessagingException, IOException {

    if (isTopLevel) {
      headers.addAll(Collections.list(p.getAllHeaders()));
    }

    if (p.isMimeType("text/*")) {
      String s = (String) p.getContent();
      String mimeType = p.getContentType();
      if (!isTopLevel) {
        headers.addAll(Collections.list(p.getAllHeaders()));
      }
      return new MessageContent(mimeType, s, headers);
    }

    if (p.isMimeType("multipart/*")) {

      // This mimetype means user agent should pick the most favorable message part
      // use preferHTML option to determine which part the return if any
      boolean isAlternative = p.isMimeType("multipart/alternative");

      Multipart mp = (Multipart) p.getContent();
      MessageContent nonPreferredContent = null;

      for (int i = 0; i < mp.getCount(); i++) {
        Part bp = mp.getBodyPart(i);
        List<Header> partHeaders = new ArrayList<>();
        MessageContent partContent = getMessageContent(bp, partHeaders, false, preferHTML);
        boolean returnPart;

        if (partContent != null) {
          returnPart =
              !isAlternative
                  || (preferHTML && partContent.mimeType.startsWith("text/html"))
                  || (!preferHTML && partContent.mimeType.startsWith("text/plain"));

          if (returnPart) {
            // Add any top level headers
            partContent.headers.addAll(headers);
            return partContent;
          } else if (nonPreferredContent == null) {
            nonPreferredContent = partContent;
          }
        }
      }
    }

    return null;
  }
}
