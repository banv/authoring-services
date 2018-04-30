package org.ihtsdo.snowowl.authoring.single.api.service.monitor;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class BranchStateMonitorTest {

	@Test
	public void testEquals() throws Exception {
		Assert.assertTrue(newMon().equals(newMon()));
	}

	@Test
	public void testMapContains() {
		Map<Class, Monitor> map = new HashMap<>();
		final BranchStateMonitor monitor = newMon();
		map.put(monitor.getClass(), monitor);
		Assert.assertTrue(map.containsValue(newMon()));
	}

	private BranchStateMonitor newMon() {
		return new BranchStateMonitor("A", "B", "MAIN/A/B", null);
	}
}
