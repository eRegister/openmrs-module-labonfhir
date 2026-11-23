package org.openmrs.module.labonfhir.api.prescription;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.hl7.fhir.r4.model.MedicationDispense;
import org.hl7.fhir.r4.model.Reference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.openmrs.Concept;
import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.TestOrder;
import org.openmrs.api.ConceptService;
import org.openmrs.api.ObsService;
import org.openmrs.api.OrderService;

@RunWith(MockitoJUnitRunner.class)
public class FetchMedicationDispensesTest {

	private static final String ORDER_UUID = "33333333-0000-4000-8000-000000000003";

	@Mock
	private OrderService orderService;

	@Mock
	private ObsService obsService;

	@Mock
	private ConceptService conceptService;

	@Mock
	private PrescriptionBundleBuilder builder;

	@Mock
	private PrescriptionSender sender;

	@InjectMocks
	private FetchMedicationDispenses task;

	private DrugOrder order;

	private Encounter encounter;

	private Concept dispensed;

	private Concept trueConcept;

	@Before
	public void setUp() {
		encounter = new Encounter();
		encounter.setUuid("enc-uuid");
		order = new DrugOrder();
		order.setUuid(ORDER_UUID);
		order.setPatient(new Patient());
		order.setEncounter(encounter);
		when(orderService.getOrderByUuid(ORDER_UUID)).thenReturn(order);

		dispensed = PrescriptionBundleBuilderTest.concept(BonoloConcepts.DISPENSED, "Dispensed");
		trueConcept = PrescriptionBundleBuilderTest.concept("true-uuid", "True");
		when(conceptService.getConceptByUuid(BonoloConcepts.DISPENSED)).thenReturn(dispensed);
		when(conceptService.getTrueConcept()).thenReturn(trueConcept);
	}

	private static MedicationDispense dispense(MedicationDispense.MedicationDispenseStatus status) {
		MedicationDispense dispense = new MedicationDispense();
		dispense.setId("dispense-1");
		dispense.setStatus(status);
		dispense.addAuthorizingPrescription(new Reference("MedicationRequest/" + ORDER_UUID));
		dispense.setWhenHandedOver(new Date());
		return dispense;
	}

	@Test
	public void processDispense_completed_shouldSaveDispensedObsAndResend() {
		MedicationDispense dispense = dispense(MedicationDispense.MedicationDispenseStatus.COMPLETED);

		task.processDispense(dispense);

		ArgumentCaptor<Obs> saved = ArgumentCaptor.forClass(Obs.class);
		verify(obsService).saveObs(saved.capture(), any(String.class));
		Obs obs = saved.getValue();
		assertThat(obs.getConcept(), sameInstance(dispensed));
		assertThat(obs.getValueCoded(), sameInstance(trueConcept));
		assertThat(obs.getOrder(), sameInstance(order));
		assertThat(obs.getEncounter(), sameInstance(encounter));
		assertThat(obs.getObsDatetime(), equalTo(dispense.getWhenHandedOver()));
		verify(sender).send(encounter);
	}

	@Test
	public void processDispense_completed_shouldDoNothingWhenAlreadyDispensed() {
		when(builder.isDispensed(order)).thenReturn(true);

		task.processDispense(dispense(MedicationDispense.MedicationDispenseStatus.COMPLETED));

		verify(obsService, never()).saveObs(any(Obs.class), anyString());
		verify(sender, never()).send(any(Encounter.class));
	}

	@Test
	public void processDispense_inProgress_shouldWriteNothing() {
		task.processDispense(dispense(MedicationDispense.MedicationDispenseStatus.INPROGRESS));

		verify(obsService, never()).saveObs(any(Obs.class), anyString());
		verify(sender, never()).send(any(Encounter.class));
	}

	@Test
	public void processDispense_shouldIgnoreOrdersThatAreNotDrugOrders() {
		when(orderService.getOrderByUuid(ORDER_UUID)).thenReturn(new TestOrder());

		task.processDispense(dispense(MedicationDispense.MedicationDispenseStatus.COMPLETED));

		verify(obsService, never()).saveObs(any(Obs.class), anyString());
	}
}
