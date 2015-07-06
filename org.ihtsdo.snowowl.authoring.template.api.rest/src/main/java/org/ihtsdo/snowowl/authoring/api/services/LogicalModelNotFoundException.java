package org.ihtsdo.snowowl.authoring.single.api.services;

import com.b2international.snowowl.core.exceptions.NotFoundException;

public class LogicalModelNotFoundException extends NotFoundException {
	/**
	 * Creates a new instance with the specified type and key.
	 *
	 * @param key  the unique key of the item which was not found (may not be {@code null})
	 */
	protected LogicalModelNotFoundException(String key) {
		super("Logical Model", key);
	}
}