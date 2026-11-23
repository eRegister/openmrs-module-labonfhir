package org.openmrs.module.labonfhir.web.controller;

import java.util.Date;
import java.util.List;

import ca.uhn.fhir.context.FhirContext;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;
import org.openmrs.DrugOrder;
import org.openmrs.Encounter;
import org.openmrs.Order;
import org.openmrs.Patient;
import org.openmrs.api.EncounterService;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.labonfhir.api.prescription.PrescriptionBundleBuilder;
import org.openmrs.module.webservices.rest.web.RestConstants;
import org.openmrs.module.webservices.rest.web.v1_0.controller.BaseRestController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Shows the Bonolo prescription Bundle LabOnFHIR would send to the FHIR server for an encounter, without sending it.
 * <p>
 * GET /openmrs/ws/rest/v1/labonfhir/prescription/preview?encounter=&lt;encounter uuid&gt;<br>
 * GET /openmrs/ws/rest/v1/labonfhir/prescription/preview?patient=&lt;patient uuid&gt; (latest encounter with drug
 * orders)
 * <p>
 * Returns the transaction Bundle as FHIR JSON, or an OperationOutcome saying why nothing would be sent. Read only:
 * it neither sends nor saves anything, and works whether or not the push is switched on.
 */
@Controller("labonfhir.prescriptionPreviewController")
@RequestMapping("/rest/" + RestConstants.VERSION_1 + "/labonfhir/prescription")
public class PrescriptionPreviewController extends BaseRestController {

	private static final MediaType FHIR_JSON = MediaType.valueOf("application/fhir+json;charset=UTF-8");

	@Autowired
	private PrescriptionBundleBuilder builder;

	@Autowired
	@Qualifier("fhirR4")
	private FhirContext fhirContext;

	@Autowired
	@Qualifier("encounterService")
	private EncounterService encounterService;

	@Autowired
	@Qualifier("patientService")
	private PatientService patientService;

	@RequestMapping(value = "/preview", method = RequestMethod.GET)
	@ResponseBody
	public ResponseEntity<String> preview(@RequestParam(value = "encounter", required = false) String encounterUuid,
	        @RequestParam(value = "patient", required = false) String patientUuid) {
		Context.requirePrivilege("Get Encounters");
		Context.requirePrivilege("Get Patients");
		Context.requirePrivilege("Get Orders");

		Encounter encounter;
		if (StringUtils.isNotBlank(encounterUuid)) {
			encounter = encounterService.getEncounterByUuid(encounterUuid.trim());
			if (encounter == null) {
				return outcome(HttpStatus.NOT_FOUND, "No encounter with uuid " + encounterUuid);
			}
		} else if (StringUtils.isNotBlank(patientUuid)) {
			Patient patient = patientService.getPatientByUuid(patientUuid.trim());
			if (patient == null) {
				return outcome(HttpStatus.NOT_FOUND, "No patient with uuid " + patientUuid);
			}
			encounter = latestWithDrugOrders(encounterService.getEncountersByPatient(patient));
			if (encounter == null) {
				return outcome(HttpStatus.NOT_FOUND, "Patient " + patientUuid + " has no encounter with drug orders");
			}
		} else {
			return outcome(HttpStatus.BAD_REQUEST, "Give an encounter or a patient uuid: ?encounter=<uuid> or ?patient=<uuid>");
		}

		PrescriptionBundleBuilder.Result result = builder.build(encounter);
		if (!result.hasBundle()) {
			return outcome(HttpStatus.OK,
			    "Encounter " + encounter.getUuid() + " would not be sent: " + result.getSkipReason());
		}
		return fhir(HttpStatus.OK, result.getBundle(), encounter.getUuid());
	}

	/** The most recent non-voided encounter that has at least one drug order. */
	static Encounter latestWithDrugOrders(List<Encounter> encounters) {
		Encounter latest = null;
		for (Encounter encounter : encounters) {
			if (encounter.getVoided() || !hasDrugOrder(encounter)) {
				continue;
			}
			if (latest == null || after(encounter.getEncounterDatetime(), latest.getEncounterDatetime())) {
				latest = encounter;
			}
		}
		return latest;
	}

	private static boolean hasDrugOrder(Encounter encounter) {
		if (encounter.getOrders() == null) {
			return false;
		}
		for (Order order : encounter.getOrders()) {
			if (order instanceof DrugOrder) {
				return true;
			}
		}
		return false;
	}

	private static boolean after(Date a, Date b) {
		return a != null && (b == null || a.after(b));
	}

	private ResponseEntity<String> outcome(HttpStatus status, String message) {
		OperationOutcome outcome = new OperationOutcome();
		outcome.addIssue()
		        .setSeverity(status.is2xxSuccessful() ? OperationOutcome.IssueSeverity.INFORMATION
		                : OperationOutcome.IssueSeverity.ERROR)
		        .setCode(status.is2xxSuccessful() ? OperationOutcome.IssueType.INFORMATIONAL
		                : status == HttpStatus.NOT_FOUND ? OperationOutcome.IssueType.NOTFOUND
		                        : OperationOutcome.IssueType.INVALID)
		        .setDiagnostics(message);
		return fhir(status, outcome, null);
	}

	private ResponseEntity<String> fhir(HttpStatus status, Resource resource, String encounterUuid) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(FHIR_JSON);
		if (encounterUuid != null) {
			headers.set("X-Encounter-Uuid", encounterUuid);
		}
		String body = fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(resource);
		return new ResponseEntity<String>(body, headers, status);
	}
}
