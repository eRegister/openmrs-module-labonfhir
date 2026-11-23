package org.openmrs.module.labonfhir.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashSet;

import org.hl7.fhir.r4.model.Task;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterProvider;
import org.openmrs.EncounterRole;
import org.openmrs.Location;
import org.openmrs.Patient;
import org.openmrs.Provider;
import org.openmrs.TestOrder;
import org.openmrs.module.fhir2.api.FhirObservationService;
import org.openmrs.module.fhir2.api.FhirTaskService;
import org.openmrs.module.labonfhir.LabOnFhirConfig;

@RunWith(MockitoJUnitRunner.class)
public class LabOrderHandlerTest {

	private static final String TEST_CONCEPT_UUID = "92d73e24-122e-4f3e-8447-fb972f01b617";

	private static final String PROVIDER_UUID = "a1b2c3d4-0000-4000-8000-000000000001";

	@Mock
	private LabOnFhirConfig config;

	@Mock
	private FhirTaskService taskService;

	@Mock
	private FhirObservationService observationService;

	@InjectMocks
	private LabOrderHandler handler;

	private Encounter encounter;

	private TestOrder order;

	@Before
	public void setUp() {
		when(config.getOrderTestUuids()).thenReturn(TEST_CONCEPT_UUID);
		when(config.getLisUserUuid()).thenReturn("lis-user-uuid");
		when(config.addObsAsTaskInput()).thenReturn(false);
		when(taskService.create(any(Task.class))).thenAnswer(new Answer<Task>() {

			@Override
			public Task answer(InvocationOnMock invocation) {
				return (Task) invocation.getArguments()[0];
			}
		});

		Concept concept = new Concept();
		concept.setUuid(TEST_CONCEPT_UUID);

		Patient patient = new Patient();

		encounter = new Encounter();
		encounter.setPatient(patient);
		encounter.setLocation(new Location());

		order = new TestOrder();
		order.setConcept(concept);
		order.setPatient(patient);
		order.setEncounter(encounter);
		encounter.addOrder(order);
	}

	private void addProvider() {
		Provider provider = new Provider();
		provider.setUuid(PROVIDER_UUID);
		// Built by hand: Encounter.addProvider() needs an authenticated OpenMRS context
		EncounterProvider encounterProvider = new EncounterProvider();
		encounterProvider.setEncounter(encounter);
		encounterProvider.setEncounterRole(new EncounterRole());
		encounterProvider.setProvider(provider);
		encounterProvider.setVoided(false);
		encounter.setEncounterProviders(new HashSet<>(Collections.singletonList(encounterProvider)));
	}

	@Test
	public void createOrder_order_shouldSetRequesterToProviderWhenEncounterHasProvider() throws Exception {
		addProvider();

		Task task = handler.createOrder(order);

		assertThat(task, notNullValue());
		assertThat(task.hasRequester(), is(true));
		assertThat(task.getRequester().getReference(), equalTo("Practitioner/" + PROVIDER_UUID));
	}

	@Test
	public void createOrder_order_shouldNotSetRequesterWhenEncounterHasNoProvider() throws Exception {
		Task task = handler.createOrder(order);

		assertThat(task, notNullValue());
		assertThat(task.hasRequester(), is(false));
	}

	@Test
	public void createOrder_encounter_shouldSetRequesterToProviderWhenEncounterHasProvider() throws Exception {
		addProvider();

		Task task = handler.createOrder(encounter);

		assertThat(task, notNullValue());
		assertThat(task.getRequester().getReference(), equalTo("Practitioner/" + PROVIDER_UUID));
	}

	@Test
	public void createOrder_encounter_shouldNotSetRequesterWhenEncounterHasNoProvider() throws Exception {
		Task task = handler.createOrder(encounter);

		assertThat(task, notNullValue());
		assertThat(task.hasRequester(), is(false));
	}
}
