package com.seatmap.api.ai;

import java.util.UUID;

import com.seatmap.api.account.Access;
import com.seatmap.api.account.AccountViews;
import com.seatmap.api.account.Role;
import com.seatmap.api.layout.LayoutService;
import com.seatmap.api.web.ForbiddenException;

import org.springframework.stereotype.Component;

/**
 * Who may use the AI assistant, and which organization a request counts against. Today:
 * editors and admins of any organization, when the assistant is switched on. This is the one
 * place to change when the assistant becomes part of some subscription plans only.
 */
@Component
class AiAccess {

	private final AiProperties properties;

	private final Access access;

	private final LayoutService layouts;

	AiAccess(AiProperties properties, Access access, LayoutService layouts) {
		this.properties = properties;
		this.access = access;
		this.layouts = layouts;
	}

	/**
	 * The organization the request is made for: the layout's when one is given (the user must
	 * be allowed to edit it), else the user's first organization they can edit layouts in.
	 */
	UUID requireOrganization(UUID layoutId) {
		this.access.requireUser();
		if (!this.properties.enabled()) {
			throw new ForbiddenException("The AI assistant isn't enabled");
		}
		if (layoutId != null) {
			return this.layouts.find(layoutId, Role.EDITOR).getOrganizationId();
		}
		return this.access.memberships()
			.stream()
			.filter((membership) -> membership.role().includes(Role.EDITOR))
			.map(AccountViews.Membership::organizationId)
			.findFirst()
			.orElseThrow(() -> new ForbiddenException("The AI assistant needs the EDITOR role in an organization"));
	}

}
