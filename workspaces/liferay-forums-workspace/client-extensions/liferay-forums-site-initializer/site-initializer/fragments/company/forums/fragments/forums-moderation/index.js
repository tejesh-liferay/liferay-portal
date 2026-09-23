/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

const forumsMod = fragmentElement.querySelector('#forumsModeration');

if (forumsMod) {
	const portalURL = Liferay.ThemeDisplay.getPortalURL();
	const scopeGroupId = Liferay.ThemeDisplay.getScopeGroupId();
	const headers = {
		'Accept': 'application/json',
		'Content-Type': 'application/json',
	};

	const buildMessageHref = function (messageData) {
		const {friendlyUrlPath, scopeKey} = messageData || {};
		if (friendlyUrlPath) {
			const siteSlug = (scopeKey || '').toLowerCase().replace(/ /g, '-');

			return (
				Liferay.ThemeDisplay.getPathFriendlyURLPublic() +
				'/' +
				siteSlug +
				'/c_c2m0thread/' +
				friendlyUrlPath
			);
		}

		return null;
	};
	const cardEl = forumsMod.querySelector('.forums-moderation__card');
	const noPermissionsEl = forumsMod.querySelector('#forumsModNoPermissions');
	const loadingEl = forumsMod.querySelector('#forumsModLoading');
	const flagList = forumsMod.querySelector('#forumsModFlagList');
	const paginationNav = forumsMod.querySelector('#forumsModPagination');
	const paginationUl = forumsMod.querySelector('#forumsModPaginationUl');

	let currentFilter = 'pending'; /* 'pending' | 'validated' | 'all' | 'bans' */
	let currentPage = 1;
	const pageSize = 20;

	/* Restore the filter tab from the URL so a refresh keeps the tab the
	   user was on instead of always reverting to "Pending". */
	const modTabLinks = forumsMod.querySelectorAll('#forumsModTabs .nav-link');
	const filterParam = new URLSearchParams(window.location.search).get(
		'filter'
	);
	if (
		filterParam &&
		[...modTabLinks].some((tab) => tab.dataset.filter === filterParam)
	) {
		currentFilter = filterParam;
		modTabLinks.forEach((tab) => {
			const active = tab.dataset.filter === filterParam;
			tab.classList.toggle('active', active);
			tab.setAttribute('aria-selected', active ? 'true' : 'false');
			if (active && flagList) {
				flagList.setAttribute('aria-labelledby', tab.id);
			}
		});
	}

	/* A ban is the Forum Banned site role held in this site. The user
	   search index stores site role names without their site, so every
	   listing is narrowed again by the user's own site briefs. */
	const forumBannedRoleURL =
		portalURL +
		'/o/headless-admin-user/v1.0/roles/by-external-reference-code/FORUM_BANNED';

	const buildBannedUserAccountsURL = function (query) {
		return (
			portalURL +
			'/o/headless-admin-user/v1.0/sites/' +
			scopeGroupId +
			'/user-accounts?filter=' +
			encodeURIComponent(
				"userGroupRoleNames/any(r:r eq 'Forum Banned')"
			) +
			query
		);
	};

	const buildForumBannedAssociationURL = function (userId) {
		return (
			forumBannedRoleURL +
			'/association/user-account/' +
			parseInt(userId, 10) +
			'/site/' +
			scopeGroupId
		);
	};

	const isBannedInSite = function (userAccount) {
		const siteBrief = (userAccount.siteBriefs || []).find(
			(site) => String(site.id) === String(scopeGroupId)
		);

		return (
			!!siteBrief &&
			(siteBrief.roleBriefs || []).some(
				(roleBrief) =>
					roleBrief.externalReferenceCode === 'FORUM_BANNED'
			)
		);
	};

	/* HATEOAS: only users allowed to assign the Forum Banned site role may
	   ban and revoke bans. */
	const canBanPromise = Liferay.Util.fetch(forumBannedRoleURL, {
		headers,
		method: 'GET',
	})
		.then((r) => {
			return r.ok ? r.json() : {};
		})
		.then((role) => {
			return !!(
				role.actions &&
				role.actions['create-site-role-user-account-association']
			);
		})
		.catch(() => false);

	/* Reason labels map */
	const reasonLabels = {
		'harassment-bullying':
			forumsMod.dataset.labelHarassmentBullying ||
			'Harassment or Bullying',
		'harmful-dangerous-acts':
			forumsMod.dataset.labelHarmfulDangerousActs ||
			'Harmful Dangerous Acts',
		'nudity-sexual-content':
			forumsMod.dataset.labelNuditySexualContent ||
			'Nudity or Sexual Content',
		'other': forumsMod.dataset.labelOther || 'Other',
		'spam': forumsMod.dataset.labelSpam || 'Spam',
	};

	const displayName = function (creator) {
		if (!creator) {
			return '';
		}
		const {familyName, givenName, name} = creator;
		const given = givenName || '';
		const family = familyName || '';

		return family && family !== 'User'
			? given + ' ' + family
			: given || name || '';
	};

	const formatDate = function (dateStr) {
		if (!dateStr) {
			return '';
		}
		const d = new Date(dateStr);

		return d.toLocaleDateString('en-US', {
			day: 'numeric',
			hour: '2-digit',
			minute: '2-digit',
			month: 'short',
			year: 'numeric',
		});
	};

	const getReasonLabel = function (reason) {
		return (
			reasonLabels[reason] ||
			reason ||
			forumsMod.dataset.labelOther ||
			'Other'
		);
	};

	const getReasonBadgeClass = function (reason) {
		if (reason === 'spam') {
			return 'label label-warning';
		}
		if (
			reason === 'harmful-dangerous-acts' ||
			reason === 'harassment-bullying' ||
			reason === 'nudity-sexual-content'
		) {
			return 'label label-danger';
		}

		return 'label label-secondary';
	};

	const showConfirmModal = function (message, confirmLabel, onConfirm) {
		const existing = document.getElementById('forumsModConfirmModal');
		if (existing) {
			existing.remove();
		}

		const modal = document.createElement('div');
		modal.id = 'forumsModConfirmModal';
		modal.className = 'modal';
		modal.style.display = 'flex';
		modal.style.backgroundColor = 'rgba(0,0,0,0.5)';
		modal.style.zIndex = '1050';
		modal.setAttribute('tabindex', '-1');
		modal.setAttribute('role', 'dialog');
		modal.setAttribute('aria-modal', 'true');
		modal.setAttribute('aria-labelledby', 'forumsModConfirmHeading');

		// XSS: every value is escaped by Liferay.Util.escapeHTML below

		modal.innerHTML = `
			<div class="modal-dialog modal-dialog-sm modal-dialog-centered modal-danger">
				<div class="modal-content">
					<div class="modal-header">
						<h1 class="modal-title" tabindex="-1">
							<div class="modal-title-indicator">
								<svg class="lexicon-icon lexicon-icon-exclamation-full" role="presentation"><use href="${Liferay.ThemeDisplay.getPathThemeImages()}/clay/icons.svg#exclamation-full"></use></svg>
							</div>
							<span id="forumsModConfirmHeading">${Liferay.Util.escapeHTML(confirmLabel)}</span>
						</h1>
						<button class="close btn btn-unstyled" type="button" id="forumsModConfirmClose" aria-label="${Liferay.Util.escapeHTML(forumsMod.dataset.labelCancel || 'Cancel')}">
							<svg class="lexicon-icon lexicon-icon-times" focusable="false" role="presentation"><use href="${Liferay.ThemeDisplay.getPathThemeImages()}/clay/icons.svg#times"></use></svg>
						</button>
					</div>
					<div class="modal-body">
						<div class="liferay-modal-body">${Liferay.Util.escapeHTML(message)}</div>
					</div>
					<div class="modal-footer">
						<div class="modal-item-last">
							<div class="btn-group-spaced" role="group">
								<button class="btn btn-secondary" type="button" id="forumsModConfirmCancel">${Liferay.Util.escapeHTML(forumsMod.dataset.labelCancel || 'Cancel')}</button>
								<button class="btn btn-danger" type="button" id="forumsModConfirmOk">${Liferay.Util.escapeHTML(confirmLabel)}</button>
							</div>
						</div>
					</div>
				</div>
			</div>`;

		document.body.appendChild(modal);
		const previousFocus = document.activeElement;

		function onKeydown(event) {
			if (event.key === 'Escape') {
				closeModal();
			}
		}

		function closeModal() {
			document.removeEventListener('keydown', onKeydown);
			modal.remove();
			if (previousFocus) {
				previousFocus.focus();
			}
		}

		modal
			.querySelector('#forumsModConfirmCancel')
			.addEventListener('click', closeModal);
		modal
			.querySelector('#forumsModConfirmClose')
			.addEventListener('click', closeModal);
		modal
			.querySelector('#forumsModConfirmOk')
			.addEventListener('click', () => {
				closeModal();
				onConfirm();
			});
		modal.addEventListener('click', (event) => {
			if (event.target === modal) {
				closeModal();
			}
		});
		document.addEventListener('keydown', onKeydown);

		modal.querySelector('.modal-title').focus();
	};

	const showToast = function (message) {
		if (Liferay.Util && Liferay.Util.openToast) {
			Liferay.Util.openToast({
				message: Liferay.Util.escapeHTML(message),
				type: 'success',
			});
		}
	};

	const showErrorToast = function (message) {
		if (Liferay.Util && Liferay.Util.openToast) {
			Liferay.Util.openToast({
				message: Liferay.Util.escapeHTML(message),
				type: 'danger',
			});
		}
	};

	/* Tab click handlers */
	modTabLinks.forEach((tab) => {
		tab.addEventListener('click', function (event) {
			event.preventDefault();
			modTabLinks.forEach((t) => {
				t.classList.remove('active');
				t.setAttribute('aria-selected', 'false');
			});
			this.classList.add('active');
			this.setAttribute('aria-selected', 'true');
			if (flagList) {
				flagList.setAttribute('aria-labelledby', this.id);
			}
			currentFilter = this.dataset.filter;
			currentPage = 1;

			const params = new URLSearchParams(window.location.search);
			params.set('filter', currentFilter);
			history.pushState(
				null,
				'',
				window.location.pathname +
					(params.toString() ? '?' + params.toString() : '')
			);

			if (currentFilter === 'bans') {
				loadBans();
			}
			else {
				loadFlags();
			}
		});
	});

	const renderPagination = function (lastPage, loadFunction) {
		if (lastPage > 1 && paginationNav && paginationUl) {
			paginationNav.style.display = '';
			let pagHtml = '';

			pagHtml +=
				'<li class="page-item' +
				(currentPage <= 1 ? ' disabled' : '') +
				'">' +
				'<a class="page-link" href="#" data-page="' +
				(currentPage - 1) +
				'" aria-label="Previous page"><span aria-hidden="true">&laquo;</span></a></li>';

			for (let p = 1; p <= lastPage && p <= 10; p++) {
				pagHtml +=
					'<li class="page-item' +
					(p === currentPage ? ' active' : '') +
					'">' +
					'<a class="page-link" href="#" data-page="' +
					p +
					'">' +
					p +
					'</a></li>';
			}

			pagHtml +=
				'<li class="page-item' +
				(currentPage >= lastPage ? ' disabled' : '') +
				'">' +
				'<a class="page-link" href="#" data-page="' +
				(currentPage + 1) +
				'" aria-label="Next page"><span aria-hidden="true">&raquo;</span></a></li>';

			// XSS: pagHtml is escaped by construction, interpolating only integers

			paginationUl.innerHTML = pagHtml;

			paginationUl.querySelectorAll('.page-link').forEach((link) => {
				link.addEventListener('click', function (event) {
					event.preventDefault();
					const p = parseInt(this.dataset.page, 10);
					if (p >= 1 && p <= lastPage) {
						currentPage = p;
						loadFunction();
					}
				});
			});
		}
		else if (paginationNav) {
			paginationNav.style.display = 'none';
		}
	};

	/* HATEOAS: check collection-level actions for write permission and show
	   or hide the card accordingly. Both loadFlags and loadBans call this,
	   since either may run first depending on the tab restored from the
	   URL. */
	const applyPermissionVisibility = function (hasPermission) {
		if (hasPermission) {
			if (noPermissionsEl) {
				noPermissionsEl.style.display = 'none';
			}
			if (cardEl) {
				cardEl.style.display = '';
			}
		}
		else {
			if (cardEl) {
				cardEl.style.display = 'none';
			}
			if (noPermissionsEl) {
				noPermissionsEl.style.display = '';
			}
		}
	};

	const loadBans = function () {
		if (loadingEl) {
			loadingEl.style.display = 'block';
		}
		flagList.innerHTML = '';
		if (paginationNav) {
			paginationNav.style.display = 'none';
		}

		Promise.all([
			canBanPromise,
			Liferay.Util.fetch(
				buildBannedUserAccountsURL(
					'&sort=givenName:asc' +
						'&page=' +
						currentPage +
						'&pageSize=' +
						pageSize
				),
				{headers, method: 'GET'}
			).then((r) => {
				return r.json();
			}),
		])
			.then(([canBan, data]) => {
				if (loadingEl) {
					loadingEl.style.display = 'none';
				}

				applyPermissionVisibility(canBan);
				if (!canBan) {
					return;
				}

				const items = (data.items || []).filter(isBannedInSite);
				const lastPage = data.lastPage || 1;

				if (!items.length) {
					flagList.innerHTML =
						'<div class="list-group-item text-secondary">' +
						Liferay.Util.escapeHTML(
							forumsMod.dataset.labelNoBans || 'No bans found.'
						) +
						'</div>';

					return;
				}

				items.forEach((userAccount) => {
					const {alternateName, familyName, givenName, id} =
						userAccount;

					const item = document.createElement('div');
					item.className =
						'list-group-item forums-moderation__flag-item';
					const infoDiv = document.createElement('div');
					infoDiv.className = 'forums-moderation__flag-info';
					const titleLink = document.createElement('span');
					titleLink.className =
						'forums-moderation__message-title font-weight-bold';
					const n =
						[givenName, familyName].filter(Boolean).join(' ') ||
						alternateName;
					titleLink.textContent = n
						? n + ' (ID: ' + id + ')'
						: (
								forumsMod.dataset.labelUserId || 'User ID: {0}'
							).replace('{0}', id);
					infoDiv.appendChild(titleLink);
					const actionsDiv = document.createElement('div');
					actionsDiv.className = 'forums-moderation__flag-actions';
					const revokeBtn = document.createElement('button');
					revokeBtn.className = 'btn btn-sm btn-outline-success';
					revokeBtn.textContent =
						forumsMod.dataset.labelRevokeBan || 'Revoke Ban';
					revokeBtn.addEventListener('click', () => {
						const message =
							forumsMod.dataset.labelConfirmRevokeBan ||
							'Are you sure you want to revoke this ban?';
						showConfirmModal(
							message,
							forumsMod.dataset.labelRevokeBan || 'Revoke Ban',
							() => {
								revokeBtn.disabled = true;
								Liferay.Util.fetch(
									buildForumBannedAssociationURL(id),
									{
										headers,
										method: 'DELETE',
									}
								)
									.then((r) => {
										if (r.ok) {
											item.style.opacity = '0.5';
											setTimeout(() => {
												item.remove();
												showToast(
													forumsMod.dataset
														.labelBanRevokedSuccessfully ||
														'Ban revoked successfully.'
												);
												if (!flagList.children.length) {
													loadBans();
												}
											}, 300);
										}
										else {
											revokeBtn.disabled = false;
										}
									})
									.catch((event) => {
										revokeBtn.disabled = false;
										console.error(event);
									});
							}
						);
					});
					actionsDiv.appendChild(revokeBtn);
					item.appendChild(infoDiv);
					item.appendChild(actionsDiv);
					flagList.appendChild(item);
				});
				renderPagination(lastPage, loadBans);
			})
			.catch((error) => {
				if (loadingEl) {
					loadingEl.style.display = 'none';
				}
				console.error('Bans load error:', error);
			});
	};

	const buildFilterParam = function () {
		if (currentFilter === 'pending') {
			return '&filter=' + encodeURIComponent('validated eq false');
		}
		else if (currentFilter === 'validated') {
			return '&filter=' + encodeURIComponent('validated eq true');
		}

		return ''; /* 'all' — no filter */
	};

	const loadFlags = function () {
		if (loadingEl) {
			loadingEl.style.display = 'block';
		}
		flagList.innerHTML = '';
		if (paginationNav) {
			paginationNav.style.display = 'none';
		}

		const url =
			portalURL +
			'/o/c/c2m0suspiciousactivities/scopes/' +
			scopeGroupId +
			'?nestedFields=threadSuspiciousActivities' +
			'&sort=dateCreated:desc' +
			'&page=' +
			currentPage +
			'&pageSize=' +
			pageSize +
			buildFilterParam();


		const bannedUserIdsPromise = Liferay.Util.fetch(
			buildBannedUserAccountsURL('&fields=id,siteBriefs&pageSize=200'),
			{headers, method: 'GET'}
		)
			.then((r) => {
				return r.json();
			})
			.then((banData) => {
				return new Set(
					(banData.items || [])
						.filter(isBannedInSite)
						.map((userAccount) => String(userAccount.id))
				);
			})
			.catch(() => new Set());

		Promise.all([
			Liferay.Util.fetch(url, {
				headers,
				method: 'GET',
			}).then((r) => {
				return r.json();
			}),
			bannedUserIdsPromise,
			canBanPromise,
		])
			.then(([data, bannedUserIds, canBan]) => {
				if (loadingEl) {
					loadingEl.style.display = 'none';
				}

				const {actions} = data;
				const hasPermission = !!(
					actions &&
					(actions['create'] || actions['post'] || actions['POST'])
				);
				applyPermissionVisibility(hasPermission);
				if (!hasPermission) {
					return;
				}

				const items = data.items || [];
				const lastPage = data.lastPage || 1;

				if (!items.length) {
					flagList.innerHTML =
						'<div class="list-group-item text-secondary">' +
						Liferay.Util.escapeHTML(
							forumsMod.dataset.labelNoFlags ||
								'No flagged messages found.'
						) +
						'</div>';

					return;
				}

				let missingDisplayPage = false;
				items.forEach((flag) => {
					const {
						actions: flagActions,
						creator: flagCreator,
						dateCreated,
						id: flagId,
						r_threadSuspiciousActivities_c_c2m0ThreadId,
						reason: flagReason,
						threadSuspiciousActivities,
						validated,
					} = flag;

					const messageData = threadSuspiciousActivities || {};
					const {creator: messageCreator, title: dataTitle} =
						messageData;
					const messageTitle =
						dataTitle ||
						'Thread #' +
							(r_threadSuspiciousActivities_c_c2m0ThreadId ||
								'?');
					const authorId = messageCreator ? messageCreator.id : null;
					const creator = flagCreator || {};
					const creatorName = displayName(creator) || 'Unknown';
					const reason = flagReason || 'other';
					const isValidated = validated === true;
					const date = formatDate(dateCreated);

					const item = document.createElement('div');
					item.className =
						'list-group-item forums-moderation__flag-item';

					/* Info column */
					const infoDiv = document.createElement('div');
					infoDiv.className = 'forums-moderation__flag-info';

					const titleHref = buildMessageHref(messageData);
					if (!titleHref) {
						missingDisplayPage = true;
					}

					const titleLink = document.createElement('a');
					titleLink.className = 'forums-moderation__message-title';
					titleLink.textContent = messageTitle;
					if (titleHref) {
						titleLink.href = titleHref;
					}
					titleLink.target = '_blank';
					titleLink.title =
						forumsMod.dataset.labelViewMessage || 'View Message';

					const metaDiv = document.createElement('div');
					metaDiv.className =
						'forums-moderation__flag-meta text-secondary small';

					/* Reporter */
					const reportedByTmpl =
						forumsMod.dataset.labelReportedBy || 'Reported by {0}';
					const reporterSpan = document.createElement('span');
					reporterSpan.textContent = reportedByTmpl.replace(
						'{0}',
						creatorName
					);

					/* Date */
					const dateSpan = document.createElement('span');
					dateSpan.textContent = date;

					/* Reason badge */
					const reasonBadge = document.createElement('span');
					reasonBadge.className = getReasonBadgeClass(reason);
					reasonBadge.textContent = getReasonLabel(reason);

					/* Status badge */
					const statusBadge = document.createElement('span');
					statusBadge.className =
						'label ' +
						(isValidated ? 'label-success' : 'label-warning');
					statusBadge.textContent = isValidated
						? forumsMod.dataset.labelValidated || 'Validated'
						: forumsMod.dataset.labelPending || 'Pending';

					metaDiv.appendChild(reporterSpan);
					metaDiv.appendChild(dateSpan);
					metaDiv.appendChild(reasonBadge);
					metaDiv.appendChild(statusBadge);

					infoDiv.appendChild(titleLink);
					infoDiv.appendChild(metaDiv);

					/* Actions column */
					const actionsDiv = document.createElement('div');
					actionsDiv.className = 'forums-moderation__flag-actions';

					/* View link — always shown (read-only) */
					const viewLink = document.createElement('a');
					viewLink.className =
						'btn btn-sm btn-outline-primary' +
						(titleHref ? '' : ' disabled');
					viewLink.textContent =
						forumsMod.dataset.labelViewMessage || 'View Message';
					if (titleHref) {
						viewLink.href = titleHref;
					}
					viewLink.target = '_blank';
					actionsDiv.appendChild(viewLink);

					/* HATEOAS: only render Validate button if the item has update/patch actions */
					if (
						flagActions &&
						(flagActions['update'] ||
							flagActions['patch'] ||
							flagActions['PUT'])
					) {
						const {PUT, patch, update} = flagActions;
						const patchHref =
							(patch && patch.href) ||
							(update && update.href) ||
							(PUT && PUT.href) ||
							portalURL +
								'/o/c/c2m0suspiciousactivities/' +
								flagId;

						const validateBtn = document.createElement('button');
						validateBtn.className = isValidated
							? 'btn btn-sm btn-outline-secondary'
							: 'btn btn-sm btn-outline-success';
						validateBtn.textContent = isValidated
							? forumsMod.dataset.labelPending || 'Pending'
							: forumsMod.dataset.labelValidate || 'Validate';
						validateBtn.addEventListener(
							'click',
							(function (flagId, flagHref, validated) {
								return function () {
									const button = this;
									button.disabled = true;
									Liferay.Util.fetch(flagHref, {
										body: JSON.stringify({
											validated: !validated,
										}),
										headers,
										method: 'PATCH',
									})
										.then((r) => {
											if (r.ok) {
												showToast(
													forumsMod.dataset
														.labelFlagValidated ||
														'Flag has been validated.'
												);
												loadFlags();
											}
											else {
												button.disabled = false;
												console.error(
													'Validate failed'
												);
											}
										})
										.catch((error) => {
											button.disabled = false;
											console.error(
												'Validate error:',
												error
											);
										});
								};
							})(flagId, patchHref, isValidated)
						);
						actionsDiv.appendChild(validateBtn);
					}

					/* Ban Author button (if the user may ban, the flag is
					   validated, has an author, and that author isn't
					   already banned) */
					if (
						canBan &&
						isValidated &&
						authorId &&
						!bannedUserIds.has(String(authorId))
					) {
						const banBtn = document.createElement('button');
						banBtn.className = 'btn btn-sm btn-outline-danger';
						banBtn.textContent =
							forumsMod.dataset.labelBanAuthor || 'Ban Author';
						banBtn.addEventListener('click', () => {
							const message =
								forumsMod.dataset.labelConfirmBanUser ||
								'Are you sure you want to ban this user?';
							showConfirmModal(
								message,
								forumsMod.dataset.labelBanAuthor ||
									'Ban Author',
								() => {
									banBtn.disabled = true;

									/* Assigning a site role the user already
									   holds is a no-op, so a concurrent ban by
									   another moderator needs no check. */
									Liferay.Util.fetch(
										buildForumBannedAssociationURL(
											authorId
										),
										{
											headers,
											method: 'POST',
										}
									)
										.then((r) => {
											if (r.ok) {
												banBtn.style.display = 'none';
												showToast(
													forumsMod.dataset
														.labelUserBanned ||
														'User has been banned.'
												);

												return;
											}

											return r
												.json()
												.catch(() => null)
												.then(
													(problemDetailJSONObject) => {
														const message =
															(problemDetailJSONObject &&
																problemDetailJSONObject.title) ||
															forumsMod.dataset
																.labelBanFailed ||
															'Unable to ban user.';

														showErrorToast(message);
														console.error(
															'Ban failed:',
															message
														);
														banBtn.disabled = false;
													}
												);
										})
										.catch((event) => {
											banBtn.disabled = false;
											console.error(event);
										});
								}
							);
						});
						actionsDiv.appendChild(banBtn);
					}

					/* HATEOAS: only render Dismiss button if the item has delete action */
					if (flagActions && flagActions['delete']) {
						const deleteHref =
							flagActions['delete'].href ||
							portalURL +
								'/o/c/c2m0suspiciousactivities/' +
								flagId;

						const dismissBtn = document.createElement('button');
						dismissBtn.className = 'btn btn-sm btn-outline-danger';
						dismissBtn.textContent =
							forumsMod.dataset.labelDismiss || 'Dismiss';
						dismissBtn.addEventListener(
							'click',
							(function (flagItem, flagDeleteHref) {
								return function () {
									const message =
										forumsMod.dataset.labelConfirmDismiss ||
										'Are you sure you want to dismiss this flag?';
									showConfirmModal(
										message,
										forumsMod.dataset.labelDismiss ||
											'Dismiss',
										() => {
											const button = dismissBtn;
											button.disabled = true;
											Liferay.Util.fetch(flagDeleteHref, {
												headers,
												method: 'DELETE',
											})
												.then((r) => {
													if (r.ok) {
														flagItem.style.opacity =
															'0.5';
														setTimeout(() => {
															flagItem.remove();
															showToast(
																forumsMod
																	.dataset
																	.labelFlagDismissed ||
																	'Flag has been dismissed.'
															);

															/* Reload if list is now empty */
															if (
																!flagList.querySelectorAll(
																	'.forums-moderation__flag-item'
																).length
															) {
																loadFlags();
															}
														}, 300);
													}
													else {
														button.disabled = false;
														console.error(
															'Dismiss failed'
														);
													}
												})
												.catch((error) => {
													button.disabled = false;
													console.error(
														'Dismiss error:',
														error
													);
												});
										}
									);
								};
							})(item, deleteHref)
						);
						actionsDiv.appendChild(dismissBtn);
					}

					/* Delete Thread button (validated flags only). Gated on
					   the ForumThread's own HATEOAS delete action rather
					   than the flag's — moderation permission over flags
					   doesn't imply delete permission over threads. */
					if (isValidated && r_threadSuspiciousActivities_c_c2m0ThreadId) {
						const threadId =
							r_threadSuspiciousActivities_c_c2m0ThreadId;

						Liferay.Util.fetch(
							portalURL + '/o/c/c2m0threads/' + threadId,
							{headers, method: 'GET'}
						)
							.then((r) => {
								return r.json();
							})
							.then((threadData) => {
								const threadActions =
									threadData && threadData.actions;

								if (!(threadActions && threadActions['delete'])) {
									return;
								}

								const deleteThreadHref =
									threadActions['delete'].href ||
									portalURL +
										'/o/c/c2m0threads/' +
										threadId;

								const deleteThreadBtn =
									document.createElement('button');
								deleteThreadBtn.className =
									'btn btn-sm btn-outline-danger';
								deleteThreadBtn.textContent =
									forumsMod.dataset.labelDeleteThread ||
									'Delete Thread';
								deleteThreadBtn.addEventListener(
									'click',
									() => {
										const message =
											forumsMod.dataset
												.labelConfirmDeleteThread ||
											'Deleting a topic is an action impossible to revert. All the replies in the topic will be removed and it will not be possible to recover them.';
										showConfirmModal(
											message,
											forumsMod.dataset
												.labelDeleteThread ||
												'Delete Thread',
											() => {
												deleteThreadBtn.disabled =
													true;
												Liferay.Util.fetch(
													deleteThreadHref,
													{
														headers,
														method: 'DELETE',
													}
												)
													.then((r) => {
														if (r.ok) {
															item.style.opacity =
																'0.5';
															setTimeout(() => {
																item.remove();

																if (
																	!flagList.querySelectorAll(
																		'.forums-moderation__flag-item'
																	).length
																) {
																	loadFlags();
																}
															}, 300);
														}
														else {
															deleteThreadBtn.disabled = false;
															console.error(
																'Delete thread failed'
															);
														}
													})
													.catch((error) => {
														deleteThreadBtn.disabled = false;
														console.error(
															'Delete thread error:',
															error
														);
													});
											}
										);
									}
								);
								actionsDiv.appendChild(deleteThreadBtn);
							})
							.catch(() => {});
					}

					item.appendChild(infoDiv);
					item.appendChild(actionsDiv);
					flagList.appendChild(item);
				});

				if (
					missingDisplayPage &&
					Liferay.Util &&
					Liferay.Util.openToast
				) {
					Liferay.Util.openToast({
						message: Liferay.Util.escapeHTML(
							forumsMod.dataset.labelDisplayPageNotConfigured ||
								'Display page is not configured for one or more messages.'
						),
						type: 'danger',
					});
				}

				/* Pagination */
				renderPagination(lastPage, loadFlags);
			})
			.catch((error) => {
				if (loadingEl) {
					loadingEl.style.display = 'none';
				}

				/* On error (e.g. 403), show the permissions warning */
				if (cardEl) {
					cardEl.style.display = 'none';
				}
				if (noPermissionsEl) {
					noPermissionsEl.style.display = '';
				}
				console.error('Moderation load error:', error);
			});
	};

	/* Initial load */
	if (currentFilter === 'bans') {
		loadBans();
	}
	else {
		loadFlags();
	}
}
