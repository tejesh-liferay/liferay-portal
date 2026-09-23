/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums.service;

import com.liferay.forums.client.LiferayApiClient;
import com.liferay.petra.string.StringBundler;
import com.liferay.petra.string.StringUtil;

import java.net.URLEncoder;

import java.nio.charset.StandardCharsets;

import java.time.Duration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * @author Roselaine Marques
 * @author Neil Griffin
 */
@Service
public class ForumNotificationService {

	public void notifyAll(
		List<Long> recipientUserIds, String kind, String authorName,
		String topicTitle, String bodyExcerpt, String url, String authToken) {

		if (recipientUserIds.isEmpty()) {
			return;
		}

		Map<Long, String> emailAddresses = _resolveEmailAddresses(
			recipientUserIds, authToken);

		List<String> bccEmailAddresses = new ArrayList<>(
			new LinkedHashSet<>(emailAddresses.values()));

		int sentCount = 0;

		for (int start = 0; start < bccEmailAddresses.size();
			 start += _BCC_BATCH_SIZE) {

			List<String> bccEmailAddressesBatch = bccEmailAddresses.subList(
				start,
				Math.min(start + _BCC_BATCH_SIZE, bccEmailAddresses.size()));

			if (_sendBulkNotification(
					bccEmailAddressesBatch, kind, authorName, topicTitle,
					bodyExcerpt, url, authToken)) {

				sentCount += bccEmailAddressesBatch.size();
			}
		}

		if (sentCount == 0) {
			_log.error(
				StringBundler.concat(
					"Forum notification reached none of ",
					recipientUserIds.size(), " recipient(s): topic=\"",
					topicTitle, "\""));
		}
		else {
			if (_log.isInfoEnabled()) {
				_log.info(
					StringBundler.concat(
						"Forum notification sent to ", sentCount, "/",
						recipientUserIds.size(),
						" recipient(s) (bulk): topic=\"", topicTitle, "\""));
			}
		}
	}

	// The in app notification is raised by an object action on the message
	// itself, because a notification points at whatever object raised it and a
	// member can open a message but not a notification row. Both recipient
	// lists go in one patch: two patches would leave the first list in place
	// and raise its notification a second time.

	//

	// The condition on that object action only checks whether the recipient
	// list is non empty, with no notion of "already sent," so it stays
	// non-empty forever unless something clears it. Any later, unrelated
	// update to this message (marking it as the answer, for one) also fires
	// onAfterUpdate and would re-satisfy that same condition, re-sending this
	// notification to the same recipients. Clearing both lists back to "" in
	// a follow-up patch closes that window: the object action's condition is
	// only true for the update that is meant to raise it.

	public void recordWebNotification(
		long messageId, List<Long> replyRecipientUserIds,
		List<Long> mentionRecipientUserIds, String authorName,
		String topicTitle, String bodyExcerpt, String authToken) {

		if ((messageId <= 0) ||
			(replyRecipientUserIds.isEmpty() &&
			 mentionRecipientUserIds.isEmpty())) {

			return;
		}

		JSONObject payloadJSONObject = new JSONObject();

		payloadJSONObject.put(
			"notificationAuthorName", authorName
		).put(
			"notificationBody", bodyExcerpt
		).put(
			"notificationMentionRecipientIds",
			_toIdList(mentionRecipientUserIds)
		).put(
			"notificationReplyRecipientIds", _toIdList(replyRecipientUserIds)
		).put(
			"notificationTopicTitle", topicTitle
		);

		try {
			_liferayApiClient.patch(
				"/o/c/c2m0messages/" + messageId, authToken,
				payloadJSONObject.toString());

			if (_log.isInfoEnabled()) {
				_log.info(
					StringBundler.concat(
						"Recorded in app recipients on message ", messageId,
						": ", replyRecipientUserIds.size(), " reply, ",
						mentionRecipientUserIds.size(), " mention"));
			}

			_clearWebNotificationRecipients(messageId, authToken);
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to record in app recipients on message ", messageId,
					": ", exception.getMessage()));
		}
	}

	private void _clearWebNotificationRecipients(
		long messageId, String authToken) {

		try {
			_liferayApiClient.patch(
				"/o/c/c2m0messages/" + messageId, authToken,
				new JSONObject(
				).put(
					"notificationMentionRecipientIds", ""
				).put(
					"notificationReplyRecipientIds", ""
				).toString());
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to clear in app recipients on message ", messageId,
					": ", exception.getMessage()));
		}
	}

	private String _encodeFilter(String filter) {
		return URLEncoder.encode(
			filter, StandardCharsets.UTF_8
		).replace(
			"+", "%20"
		);
	}

	private NotificationTemplateContent _getNotificationTemplateContent(
		String kind, String authToken) {

		return _notificationTemplateContents.computeIfAbsent(
			kind,
			key -> {
				String externalReferenceCode = Objects.equals(key, "mention") ?
					_NOTIFICATION_TEMPLATE_ERC_MENTION :
						_NOTIFICATION_TEMPLATE_ERC_REPLY;

				try {
					JSONObject responseJSONObject = new JSONObject(
						_liferayApiClient.get(
							StringBundler.concat(
								"/o/notification/v1.0/notification-templates",
								"/by-external-reference-code/",
								externalReferenceCode),
							authToken));

					JSONObject recipientJSONObject =
						responseJSONObject.getJSONArray(
							"recipients"
						).getJSONObject(
							0
						);

					NotificationTemplateContent notificationTemplateContent =
						new NotificationTemplateContent();

					notificationTemplateContent._body =
						responseJSONObject.getJSONObject(
							"body"
						).getString(
							"en_US"
						);
					notificationTemplateContent._from =
						recipientJSONObject.getString("from");
					notificationTemplateContent._fromName =
						recipientJSONObject.getJSONObject(
							"fromName"
						).getString(
							"en_US"
						);
					notificationTemplateContent._subject =
						responseJSONObject.getJSONObject(
							"subject"
						).getString(
							"en_US"
						);

					return notificationTemplateContent;
				}
				catch (Exception exception) {
					_log.error(
						StringBundler.concat(
							"Unable to load notification template \"",
							externalReferenceCode, "\": ",
							exception.getMessage()));

					return null;
				}
			});
	}

	private String _renderMergeTags(
		String authorName, String bodyExcerpt, String template,
		String topicTitle, String url) {

		template = StringUtil.replace(
			template, "[%C2M0NOTIFICATION_AUTHORNAME%]", authorName);
		template = StringUtil.replace(
			template, "[%C2M0NOTIFICATION_BODYEXCERPT%]", bodyExcerpt);
		template = StringUtil.replace(
			template, "[%C2M0NOTIFICATION_NOTIFICATIONURL%]", url);
		template = StringUtil.replace(
			template, "[%C2M0NOTIFICATION_TOPICTITLE%]", topicTitle);

		return template;
	}

	private void _resolveEmailAddresses(
		List<Long> userIds, Map<Long, String> emailAddresses,
		String authToken) {

		StringBundler sb = new StringBundler(userIds.size() * 4);

		for (Long userId : userIds) {
			sb.append("id eq '");
			sb.append(userId);
			sb.append("'");
			sb.append(" or ");
		}

		if (sb.index() > 0) {
			sb.setIndex(sb.index() - 1);
		}

		String filter = sb.toString();

		try {
			String response = _liferayApiClient.get(
				StringBundler.concat(
					"/o/headless-admin-user/v1.0/user-accounts?fields=id,",
					"emailAddress&pageSize=", userIds.size(), "&filter=",
					_encodeFilter(filter)),
				authToken);

			JSONArray itemsJSONArray = new JSONObject(
				response
			).optJSONArray(
				"items"
			);

			if (itemsJSONArray != null) {
				for (int i = 0; i < itemsJSONArray.length(); i++) {
					JSONObject itemJSONObject = itemsJSONArray.optJSONObject(i);

					if (itemJSONObject == null) {
						continue;
					}

					long userId = itemJSONObject.optLong("id", 0L);
					String emailAddress = itemJSONObject.optString(
						"emailAddress", "");

					if ((userId > 0L) && !emailAddress.isBlank()) {
						emailAddresses.put(userId, emailAddress);
					}
				}
			}
		}
		catch (Exception exception) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					"Unable to resolve recipient email addresses: " +
						exception.getMessage());
			}
		}
	}

	private Map<Long, String> _resolveEmailAddresses(
		List<Long> userIds, String authToken) {

		Map<Long, String> emailAddresses = new HashMap<>();

		for (int start = 0; start < userIds.size();
			 start += _EMAIL_LOOKUP_BATCH_SIZE) {

			_resolveEmailAddresses(
				userIds.subList(
					start,
					Math.min(start + _EMAIL_LOOKUP_BATCH_SIZE, userIds.size())),
				emailAddresses, authToken);
		}

		return emailAddresses;
	}

	private boolean _sendBulkNotification(
		List<String> bccEmailAddresses, String kind, String authorName,
		String topicTitle, String bodyExcerpt, String url, String authToken) {

		NotificationTemplateContent notificationTemplateContent =
			_getNotificationTemplateContent(kind, authToken);

		if (notificationTemplateContent == null) {
			return false;
		}

		String fullUrl = _liferayApiClient.getBaseUrl() + url;

		JSONObject recipientJSONObject = new JSONObject();

		recipientJSONObject.put(
			"bcc", String.join(",", bccEmailAddresses)
		).put(
			"from", notificationTemplateContent._from
		).put(
			"fromName", notificationTemplateContent._fromName
		).put(
			"to", notificationTemplateContent._from
		);

		JSONObject payloadJSONObject = new JSONObject();

		payloadJSONObject.put(
			"body",
			_renderMergeTags(
				authorName, bodyExcerpt, notificationTemplateContent._body,
				topicTitle, fullUrl)
		).put(
			"recipients",
			new JSONArray(
			).put(
				recipientJSONObject
			)
		).put(
			"subject",
			_renderMergeTags(
				authorName, bodyExcerpt, notificationTemplateContent._subject,
				topicTitle, fullUrl)
		).put(
			"type", "email"
		);

		return _liferayApiClient.postAsync(
			"/o/notification/v1.0/notification-queue-entries", authToken,
			payloadJSONObject.toString()
		).map(
			response -> true
		).onErrorResume(
			throwable -> {
				_log.error(
					"Unable to send bulk forum notification: " +
						throwable.getMessage());

				return Mono.just(false);
			}
		).blockOptional(
			Duration.ofSeconds(_notificationTimeoutSeconds)
		).orElse(
			false
		);
	}

	private String _toIdList(List<Long> userIds) {
		StringBundler sb = new StringBundler(userIds.size() * 2);

		for (Long userId : userIds) {
			sb.append(userId);
			sb.append(",");
		}

		if (sb.index() > 0) {
			sb.setIndex(sb.index() - 1);
		}

		return sb.toString();
	}

	private static final int _BCC_BATCH_SIZE = 200;

	private static final int _EMAIL_LOOKUP_BATCH_SIZE = 50;

	private static final String _NOTIFICATION_TEMPLATE_ERC_MENTION =
		"C2M0_NOTIFICATION_EMAIL_MENTION";

	private static final String _NOTIFICATION_TEMPLATE_ERC_REPLY =
		"C2M0_NOTIFICATION_EMAIL_REPLY";

	private static final Log _log = LogFactory.getLog(
		ForumNotificationService.class);

	@Autowired
	private LiferayApiClient _liferayApiClient;

	private final Map<String, NotificationTemplateContent>
		_notificationTemplateContents = new ConcurrentHashMap<>();

	@Value("${forums.notification.timeout.seconds:60}")
	private int _notificationTimeoutSeconds;

	private static class NotificationTemplateContent {

		private String _body;
		private String _from;
		private String _fromName;
		private String _subject;

	}

}