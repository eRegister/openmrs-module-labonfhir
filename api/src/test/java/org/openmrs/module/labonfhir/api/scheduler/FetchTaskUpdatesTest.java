package org.openmrs.module.labonfhir.api.scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Task;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.openmrs.module.fhir2.api.FhirTaskService;

@RunWith(MockitoJUnitRunner.class)
public class FetchTaskUpdatesTest {

	private static final String OPENMRS_TASK_UUID = "44fdc8ad-fe4d-499b-93a8-8a991c1d477e";

	private static final String OPENELIS_TASK_UUID = "55fdc8ad-fe4d-499b-93a8-8a991c1d4788";

	private static final String TASK_IDENTIFIER_SYSTEM = "http://fhir.openmrs.org/ext/task/identifier";

	private FetchTaskUpdates updateTask;

	@Mock
	private FhirTaskService taskService;

	@Before
	public void setup() {
		updateTask = new FetchTaskUpdates();
		updateTask.setTaskService(taskService);
	}

	private Bundle bundleWithLisTask(Task.TaskStatus status) {
		Task lisTask = new Task();
		lisTask.setId(OPENELIS_TASK_UUID);
		lisTask.setStatus(status);
		lisTask.addIdentifier().setSystem(TASK_IDENTIFIER_SYSTEM).setValue(OPENMRS_TASK_UUID);

		Bundle bundle = new Bundle();
		bundle.addEntry().setResource(lisTask);
		return bundle;
	}

	private Task openmrsTask(Task.TaskStatus status) {
		Task task = new Task();
		task.setId(OPENMRS_TASK_UUID);
		task.setStatus(status);
		return task;
	}

	@Test
	public void updateTasksInBundle_shouldSaveStatusChangeWithoutNewOutput() {
		when(taskService.get(OPENMRS_TASK_UUID)).thenReturn(openmrsTask(Task.TaskStatus.REQUESTED));

		updateTask.updateTasksInBundle(Collections.singletonList(bundleWithLisTask(Task.TaskStatus.COMPLETED)));

		ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
		verify(taskService).update(eq(OPENMRS_TASK_UUID), saved.capture());
		assertThat(saved.getValue().getStatus(), equalTo(Task.TaskStatus.COMPLETED));
	}

	@Test
	public void updateTasksInBundle_shouldNotSaveWhenNothingChanged() {
		when(taskService.get(OPENMRS_TASK_UUID)).thenReturn(openmrsTask(Task.TaskStatus.COMPLETED));

		updateTask.updateTasksInBundle(Collections.singletonList(bundleWithLisTask(Task.TaskStatus.COMPLETED)));

		verify(taskService, never()).update(anyString(), any(Task.class));
	}

	@Test
	public void updateTasksInBundle_shouldIgnoreTasksNotFoundInOpenmrs() {
		when(taskService.get(OPENMRS_TASK_UUID)).thenReturn(null);

		updateTask.updateTasksInBundle(Collections.singletonList(bundleWithLisTask(Task.TaskStatus.COMPLETED)));

		verify(taskService, never()).update(anyString(), any(Task.class));
	}
}
