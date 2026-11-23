package org.openmrs.module.labonfhir.api.prescription;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;
import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.Order;
import org.openmrs.TestOrder;

public class PrescriptionEncounterListenerTest {

	private static Encounter encounter(String uuid, Order... orders) {
		Encounter encounter = new Encounter();
		encounter.setUuid(uuid);
		for (Order order : orders) {
			order.setEncounter(encounter);
		}
		encounter.setOrders(new HashSet<>(Arrays.asList(orders)));
		return encounter;
	}

	private static DrugOrder drugOrder(Order.Action action, Order previous) {
		DrugOrder order = new DrugOrder();
		order.setAction(action);
		order.setPreviousOrder(previous);
		return order;
	}

	@Test
	public void encountersToSend_shouldSendAnEncounterWithDrugOrders() {
		Encounter encounter = encounter("e1", drugOrder(Order.Action.NEW, null));

		assertThat(PrescriptionEncounterListener.encountersToSend(encounter).keySet(), contains("e1"));
	}

	@Test
	public void encountersToSend_shouldIgnoreEncountersWithoutDrugOrders() {
		Encounter encounter = encounter("e1", new TestOrder());

		assertThat(PrescriptionEncounterListener.encountersToSend(encounter).keySet(), empty());
	}

	@Test
	public void encountersToSend_shouldAlsoResendThePrescriptionOfARevisedOrder() {
		DrugOrder original = drugOrder(Order.Action.NEW, null);
		encounter("e0", original);
		Encounter revision = encounter("e1", drugOrder(Order.Action.REVISE, original));

		assertThat(PrescriptionEncounterListener.encountersToSend(revision).keySet(), contains("e0", "e1"));
	}

	@Test
	public void encountersToSend_shouldResendThePrescriptionOfADiscontinuedOrder() {
		DrugOrder original = drugOrder(Order.Action.NEW, null);
		encounter("e0", original);
		Encounter discontinue = encounter("e1", drugOrder(Order.Action.DISCONTINUE, original));

		// e1 is sent too; the builder then skips it because a discontinue order is not a prescription line
		assertThat(PrescriptionEncounterListener.encountersToSend(discontinue).keySet(), contains("e0", "e1"));
	}
}
