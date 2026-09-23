/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

/* The forums fragments on one page load some of the same resources (the
   user's site roles, the Forum Banned role, the current thread). Share one
   GET per URL across them for the life of the page; every caller gets its
   own Response, so bodies can be read and mutated independently. */
const forumsSharedFetch = function (url, options) {
	if (!window.forumsSharedFetchCache) {
		window.forumsSharedFetchCache = {};

		Liferay.once('beforeNavigate', () => {
			delete window.forumsSharedFetchCache;
		});
	}

	const cache = window.forumsSharedFetchCache;

	if (!cache[url]) {
		cache[url] = Liferay.Util.fetch(url, options)
			.then((response) => {
				return response.text().then((text) => {
					return {status: response.status, text};
				});
			})
			.catch((error) => {
				delete cache[url];

				throw error;
			});
	}

	return cache[url].then(({status, text}) => {
		return new Response(
			[204, 205, 304].includes(status) ? null : text,
			{headers: {'Content-Type': 'application/json'}, status}
		);
	});
};

const relatedTopics = fragmentElement.querySelector('#forumsRelatedTopics');

if (relatedTopics && !document.body.classList.contains('has-edit-mode-menu')) {
	const portalURL = Liferay.ThemeDisplay.getPortalURL();
	const scopeGroupId = Liferay.ThemeDisplay.getScopeGroupId();
	const headers = {
		'Accept': 'application/json',
		'Content-Type': 'application/json',
	};

	const listEl = relatedTopics.querySelector('#forumsRelatedTopicsList');
	const loadingEl = relatedTopics.querySelector(
		'#forumsRelatedTopicsLoading'
	);

	/* URL params */
	const urlParams = new URLSearchParams(window.location.search);
	let currentMessageId = urlParams.get('messageId');

	/* resolvedThread is the thread when the caller already loaded it (the
	   mapped ERC lookup returns the full entry), so it is not fetched again */
	const runRelatedTopics = function (resolvedMessageId, resolvedThread) {
		currentMessageId = resolvedMessageId;

		if (!currentMessageId) {
			if (loadingEl) {
				loadingEl.remove();
			}
			relatedTopics.style.display = 'none';

			return;
		}

		/* First, get the current message to find its category. Same URL as
		   forums-message-detail, so the request is shared. */
		(resolvedThread
			? Promise.resolve(resolvedThread)
			: forumsSharedFetch(
					portalURL + '/o/c/c2m0threads/' + currentMessageId,
					{
						headers,
						method: 'GET',
					}
				).then((r) => {
					return r.json();
				})
		)
			.then((msg) => {
				const categoryBrief =
					(msg.taxonomyCategoryBriefs || [])[0] || null;
				const categoryId = categoryBrief
					? categoryBrief.taxonomyCategoryId
					: null;

				/* Fetch other messages from the same category.
				   taxonomyCategoryIds is a CollectionEntityField — OData
				   rejects "eq" on collection fields ("Collection not
				   allowed"); "in" is the working syntax. */
				const filterParts = [];
				if (categoryId) {
					filterParts.push('taxonomyCategoryIds in (' + categoryId + ')');
				}

				let url =
					portalURL +
					'/o/c/c2m0threads/scopes/' +
					scopeGroupId +
					'?pageSize=6&sort=lastPostDate:desc';
				if (filterParts.length) {
					url +=
						'&filter=' +
						encodeURIComponent(filterParts.join(' and '));
				}

				return Liferay.Util.fetch(url, {headers, method: 'GET'});
			})
			.then((r) => {
				return r.json();
			})
			.then((data) => {
				if (loadingEl) {
					loadingEl.remove();
				}

				const items = (data.items || [])
					.filter(({id}) => {
						return String(id) !== String(currentMessageId);
					})
					.slice(0, 5);

				if (!items.length) {
					listEl.innerHTML =
						'<div class="text-secondary text-center py-2">' +
						Liferay.Util.escapeHTML(
							relatedTopics.dataset.labelNoRelated ||
								'No related topics found.'
						) +
						'</div>';

					return;
				}

				let html = '';
				let missingDisplayPage = false;
				items.forEach(
					({
						friendlyUrlPath,
						scopeKey,
						title: messageTitle,
						validatedFlagCount,
					}) => {
						const title =
							messageTitle ||
							relatedTopics.dataset.labelUntitled ||
							'Untitled';
						const isFlagged =
							parseInt(validatedFlagCount, 10) > 0;

						let flaggedBadge = '';
						if (isFlagged) {
							const flaggedText = Liferay.Util.escapeHTML(
								relatedTopics.dataset.labelFlagged || 'Flagged'
							);
							flaggedBadge =
								'<span class="text-danger ml-2" style="font-size:0.85em"><svg class="lexicon-icon lexicon-icon-warning-full" role="presentation" viewBox="0 0 16 16" fill="currentColor" width="12" height="12"><path d="M16 14.5L8 1 0 14.5h16zM8 13c-.6 0-1-.4-1-1s.4-1 1-1 1 .4 1 1-.4 1-1 1zm1-3H7V6h2v4z"/></svg> ' +
								flaggedText +
								'</span>';
						}

						if (friendlyUrlPath) {
							const siteSlug = (scopeKey || '')
								.toLowerCase()
								.replace(/ /g, '-');
							const messageHref =
								Liferay.ThemeDisplay.getPathFriendlyURLPublic() +
								'/' +
								siteSlug +
								'/c_c2m0thread/' +
								friendlyUrlPath;
							html +=
								'<a href="' +
								Liferay.Util.escapeHTML(messageHref) +
								'" class="list-group-item list-group-item-action d-flex justify-content-between align-items-center">' +
								'<span>' +
								Liferay.Util.escapeHTML(title) +
								'</span>' +
								flaggedBadge +
								'</a>';
						}
						else {
							missingDisplayPage = true;
							html +=
								'<div class="list-group-item d-flex justify-content-between align-items-center">' +
								'<span>' +
								Liferay.Util.escapeHTML(title) +
								'</span>' +
								flaggedBadge +
								'</div>';
						}
					}
				);

				// XSS: html is escaped by Liferay.Util.escapeHTML where it is built

				listEl.innerHTML = html;

				if (
					missingDisplayPage &&
					Liferay.Util &&
					Liferay.Util.openToast
				) {
					Liferay.Util.openToast({
						message: Liferay.Util.escapeHTML(
							relatedTopics.dataset
								.labelDisplayPageNotConfigured ||
								'Display page is not configured for one or more messages.'
						),
						type: 'danger',
					});
				}
			})
			.catch((error) => {
				if (loadingEl) {
					loadingEl.remove();
				}
				listEl.innerHTML =
					'<div class="text-secondary text-center py-2">' +
					Liferay.Util.escapeHTML(
						relatedTopics.dataset.labelUnableToLoad ||
							'Unable to load related topics.'
					) +
					'</div>';
				console.error('ForumsRelatedTopics error:', error);
			});
	}; // end runRelatedTopics

	/* Resolve messageId: ?messageId param → mapped reply ERC → mapped message ERC → URL path slug */
	if (currentMessageId) {
		runRelatedTopics(currentMessageId);
	}
	else {

		/* Reply ERC takes priority — set when this fragment is on a Forum Message Display Page */
		const replyErcEl = relatedTopics.querySelector(
			'#forumsRelatedTopicsReplyERC'
		);
		let replyErc = replyErcEl ? replyErcEl.textContent.trim() : null;
		if (replyErc === 'Mappable Reply ERC') {
			replyErc = null;
		}

		if (replyErc) {
			forumsSharedFetch(
				portalURL +
					'/o/c/c2m0messages/scopes/' +
					scopeGroupId +
					'/by-external-reference-code/' +
					encodeURIComponent(replyErc),
				{
					headers,
					method: 'GET',
				}
			)
				.then((r) => {
					if (!r.ok) {
						throw new Error('Not found');
					}

					return r.json();
				})
				.then((reply) => {
					const parentMessageId =
						reply.r_threadMessages_c_c2m0ThreadId;
					runRelatedTopics(
						parentMessageId ? String(parentMessageId) : null
					);
				})
				.catch(() => {
					runRelatedTopics(null);
				});
		}
		else {
			const ercEl = relatedTopics.querySelector(
				'#forumsRelatedTopicsERC'
			);
			let erc = ercEl ? ercEl.textContent.trim() : null;
			if (erc === 'Mappable Message ERC') {
				erc = null;
			}

			if (!erc) {
				if (loadingEl) {
					loadingEl.remove();
				}
				listEl.innerHTML =
					'<div class="text-secondary text-center py-2">' +
					Liferay.Util.escapeHTML(
						relatedTopics.dataset.labelErcNotMapped ||
							'Message ERC is not mapped.'
					) +
					'</div>';
			}
			else {
				forumsSharedFetch(
					portalURL +
						'/o/c/c2m0threads/scopes/' +
						scopeGroupId +
						'/by-external-reference-code/' +
						encodeURIComponent(erc),
					{
						headers,
						method: 'GET',
					}
				)
					.then((r) => {
						if (!r.ok) {
							throw new Error('Not found');
						}

						return r.json();
					})
					.then((data) => {
						runRelatedTopics(
							data.id ? String(data.id) : null,
							data
						);
					})
					.catch(() => {
						runRelatedTopics(null);
					});
			}
		}
	}
}
