package org.openmrs.module.labonfhir.web.controller;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;

import org.junit.Test;
import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.Order;
import org.openmrs.TestOrder;

public class PrescriptionPreviewControllerTest {

	private static Encounter encounter(long time, boolean voided, Order... orders) {
		Encounter encounter = new Encounter();
		encounter.setEncounterDatetime(new Date(time));
		encounter.setVoided(voided);
		encounter.setOrders(new HashSet<Order>(Arrays.asList(orders)));
		return encounter;
	}

	@Test
	public void latestWithDrugOrders_shouldPickTheMostRecentEncounterWithADrugOrder() {
		Encounter older = encounter(1000, false, new DrugOrder());
		Encounter newer = encounter(2000, false, new DrugOrder());
		Encounter newestLabOnly = encounter(3000, false, new TestOrder());
		Encounter newestVoided = encounter(4000, true, new DrugOrder());

		assertThat(PrescriptionPreviewController
		        .latestWithDrugOrders(Arrays.asList(newer, newestLabOnly, older, newestVoided)), sameInstance(newer));
	}

	@Test
	public void latestWithDrugOrders_shouldReturnNullWhenNoEncounterHasDrugOrders() {
		assertThat(PrescriptionPreviewController.latestWithDrugOrders(
		    Collections.singletonList(encounter(1000, false, new TestOrder()))), nullValue());
	}
}
