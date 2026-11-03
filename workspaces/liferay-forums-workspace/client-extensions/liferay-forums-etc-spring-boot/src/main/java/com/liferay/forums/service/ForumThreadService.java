/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.forums.service;

import com.liferay.forums.client.LiferayApiClient;
import com.liferay.petra.string.StringBundler;

import java.net.URLEncoder;

import java.nio.charset.StandardCharsets;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author Roselaine Marques
 */
@Service
public class ForumThreadService {

	public void backfillLastPostDates(String authToken) {
		int count = 0;

		for (long siteId : _getSiteIds(authToken)) {
			count += _backfillLastPostDates(siteId, authToken);
		}

		if ((count > 0) && _log.isInfoEnabled()) {
			_log.info(
				StringBundler.concat(
					"Set the lastPostDate of ", count,
					" threads to their creation date"));
		}
	}

	public void revertPriority(
		long threadId, double priority, String authToken) {

		if (threadId <= 0) {
			return;
		}

		try {
			_liferayApiClient.patch(
				"/o/c/c2m0threads/" + threadId, authToken,
				new JSONObject(
				).put(
					"priority", priority
				).toString());

			if (_log.isInfoEnabled()) {
				_log.info(
					StringBundler.concat(
						"Reverted the priority of thread ", threadId, " to ",
						priority));
			}
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to revert the priority of thread ", threadId, ": ",
					exception.getMessage()));
		}
	}

	public void setMissingLastPostDate(long threadId, String authToken) {
		if (threadId <= 0) {
			return;
		}

		try {
			JSONObject threadJSONObject = new JSONObject(
				_liferayApiClient.get(
					"/o/c/c2m0threads/" + threadId +
						"?fields=dateCreated,lastPostDate",
					authToken));

			if (!threadJSONObject.optString("lastPostDate").isEmpty()) {
				return;
			}

			_patchLastPostDate(
				threadId, threadJSONObject.optString("dateCreated"),
				authToken);
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to set the missing lastPostDate of thread ",
					threadId, ": ", exception.getMessage()));
		}
	}

	public void updateLastPostDate(long threadId, String authToken) {
		if (threadId <= 0) {
			if (_log.isWarnEnabled()) {
				_log.warn(
					StringBundler.concat(
						"Unable to update lastPostDate for thread ", threadId,
						" without a valid id"));
			}

			return;
		}

		try {
			_liferayApiClient.patch(
				"/o/c/c2m0threads/" + threadId, authToken,
				new JSONObject(
				).put(
					"lastPostDate",
					Instant.now(
					).truncatedTo(
						ChronoUnit.SECONDS
					).toString()
				).toString());
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to update lastPostDate for thread ", threadId, ": ",
					exception.getMessage()));
		}
	}

	private int _backfillLastPostDates(long siteId, String authToken) {
		int count = 0;
		Set<Long> threadIds = new HashSet<>();

		try {
			while (true) {
				JSONArray itemsJSONArray = new JSONObject(
					_liferayApiClient.get(
						StringBundler.concat(
							"/o/c/c2m0threads/scopes/", siteId,
							"?fields=dateCreated,id&pageSize=", _PAGE_SIZE,
							"&filter=",
							URLEncoder.encode(
								"lastPostDate eq null",
								StandardCharsets.UTF_8)),
						authToken)
				).optJSONArray(
					"items"
				);

				if ((itemsJSONArray == null) || itemsJSONArray.isEmpty()) {
					return count;
				}

				boolean patched = false;

				for (int i = 0; i < itemsJSONArray.length(); i++) {
					JSONObject threadJSONObject =
						itemsJSONArray.getJSONObject(i);

					long threadId = threadJSONObject.optLong("id");

					if (!threadIds.add(threadId)) {
						continue;
					}

					_patchLastPostDate(
						threadId, threadJSONObject.optString("dateCreated"),
						authToken);

					count++;
					patched = true;
				}

				if (!patched) {
					return count;
				}
			}
		}
		catch (Exception exception) {
			_log.error(
				StringBundler.concat(
					"Unable to set the missing lastPostDate of the threads of ",
					"site ", siteId, ": ", exception.getMessage()));
		}

		return count;
	}

	private List<Long> _getSiteIds(String authToken) {
		List<Long> siteIds = new ArrayList<>();

		int page = 1;

		try {
			while (true) {
				JSONObject pageJSONObject = new JSONObject(
					_liferayApiClient.get(
						StringBundler.concat(
							"/o/headless-admin-site/v1.0/sites?fields=id&page=",
							page, "&pageSize=", _PAGE_SIZE),
						authToken));

				JSONArray itemsJSONArray = pageJSONObject.optJSONArray(
					"items");

				if (itemsJSONArray != null) {
					for (int i = 0; i < itemsJSONArray.length(); i++) {
						siteIds.add(
							itemsJSONArray.getJSONObject(
								i
							).optLong(
								"id"
							));
					}
				}

				if (page >= pageJSONObject.optInt("lastPage", 1)) {
					return siteIds;
				}

				page++;
			}
		}
		catch (Exception exception) {
			_log.error("Unable to list the sites: " + exception.getMessage());
		}

		return siteIds;
	}

	private void _patchLastPostDate(
		long threadId, String lastPostDate, String authToken) {

		_liferayApiClient.patch(
			"/o/c/c2m0threads/" + threadId, authToken,
			new JSONObject(
			).put(
				"lastPostDate", lastPostDate
			).toString());
	}

	private static final int _PAGE_SIZE = 200;

	private static final Log _log = LogFactory.getLog(ForumThreadService.class);

	@Autowired
	private LiferayApiClient _liferayApiClient;

}