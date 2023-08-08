package org.openmrs.module.labonfhir.api.scheduler;

import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import lombok.AccessLevel;
import lombok.Setter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.SessionFactory;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.codesystems.TaskStatus;
import org.openmrs.api.ConceptService;
import org.openmrs.module.fhir2.FhirConstants;
import org.openmrs.module.fhir2.api.FhirDiagnosticReportService;
import org.openmrs.module.fhir2.api.FhirObservationService;
import org.openmrs.module.fhir2.api.FhirTaskService;
import org.openmrs.module.fhir2.api.dao.FhirObservationDao;
import org.openmrs.module.fhir2.api.translators.ObservationReferenceTranslator;
import org.openmrs.module.fhir2.api.translators.ObservationValueTranslator;
import org.openmrs.module.labonfhir.LabOnFhirConfig;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
@Setter(AccessLevel.PACKAGE)
public class FetchTaskUpdates extends AbstractTask implements ApplicationContextAware {

	private static Log log = LogFactory.getLog(FetchTaskUpdates.class);

	private static ApplicationContext applicationContext;

	private static String LOINC_SYSTEM = "http://loinc.org";

	private static String DISA_LS_SYSTEM = "http://health.gov.ls/laboratory-services";

	@Autowired
	private LabOnFhirConfig config;

	@Autowired
	@Qualifier("labOrderFhirClient")
	private IGenericClient client;

	@Autowired
	private FhirTaskService taskService;

	@Autowired
	private FhirDiagnosticReportService diagnosticReportService;

	@Autowired
	FhirObservationDao observationDao;

	@Autowired
	FhirObservationService observationService;

	@Autowired
	ObservationReferenceTranslator observationReferenceTranslator;

	@Autowired
	ObservationValueTranslator observationValueTranslator;

	@Autowired
	@Qualifier("sessionFactory")
	SessionFactory sessionFactory;

	@Override
	public void execute() {

		try {
			applicationContext.getAutowireCapableBeanFactory().autowireBean(this);
		} catch (Exception e) {
			// return;
		}

		if (!config.isLisEnabled()) {
			return;
		}

		try {
			// Get List of Tasks that belong to this instance and update them
			updateTasksInBundle(client.search().forResource(Task.class)
					.where(Task.IDENTIFIER.hasSystemWithAnyCode(FhirConstants.OPENMRS_FHIR_EXT_TASK_IDENTIFIER))
					.where(Task.STATUS.exactly().code(TaskStatus.COMPLETED.toCode())).returnBundle(Bundle.class)
					.execute());
		} catch (Exception e) {
			log.error("ERROR executing FetchTaskUpdates : " + e.toString() + getStackTrace(e));
		}

		super.startExecuting();
	}

	@Override
	public void shutdown() {
		log.debug("shutting down FetchTaskUpdates Task");

		this.stopExecuting();
	}

	private void updateTasksInBundle(Bundle taskBundle) {

		for (Iterator tasks = taskBundle.getEntry().iterator(); tasks.hasNext();) {
			String openmrsTaskUuid = null;

			try {
				// Read incoming LIS Task
				Task openelisTask = (Task) ((Bundle.BundleEntryComponent) tasks.next()).getResource();
				openmrsTaskUuid = openelisTask.getIdentifierFirstRep().getValue();

				// Find original openmrs task using Identifier
				Task openmrsTask = taskService.get(openmrsTaskUuid);

				// Only update if matching OpenMRS Task found
				if (openmrsTask != null) {
					// Handle status
					openmrsTask.setStatus(openelisTask.getStatus());

					Boolean taskOutPutUpdated = false;
					if (openelisTask.hasOutput()) {
						// openmrsTask.setOutput(openelisTask.getOutput());
						taskOutPutUpdated = updateOutput(openelisTask.getOutput(), openmrsTask);
					}
					if (taskOutPutUpdated) {
						taskService.update(openmrsTaskUuid, openmrsTask);
					}
				}
			} catch (Exception e) {
				log.error("Could not save task " + openmrsTaskUuid + ":" + e.toString() + getStackTrace(e));
			}
		}
	}

	private Boolean updateOutput(List<Task.TaskOutputComponent> output, Task openmrsTask) {

		Reference encounterReference = openmrsTask.getEncounter();
		List<Reference> basedOn = openmrsTask.getBasedOn();
		List<String> allExistingLoincCodes = new ArrayList<>();
		//allExistingLoincCodes list contains loinc codes of DiagReports already in OpenMRS (i.e. processed)
		Boolean taskOutPutUpdated = false;
		// openmrsTask.getOutput().stream().map(ouput -> ouput.getType().getCoding());
		openmrsTask.getOutput().forEach(out -> {
			out.getType().getCoding().stream().filter(coding -> coding.hasSystem())
					.filter(coding -> coding.getSystem().equals(LOINC_SYSTEM))
					.forEach(coding -> {
						allExistingLoincCodes.add(coding.getCode());
					});
		});

		if (!output.isEmpty()) {
						
			log.error("Got a task with some output ...");
			log.error("Got a task "+openmrsTask.getId());
			// Save each output entry
			for (Iterator outputRefI = output.stream().iterator(); outputRefI.hasNext();) {
				Task.TaskOutputComponent outputRef = (Task.TaskOutputComponent) outputRefI.next();
				String openelisDiagnosticReportUuid = ((Reference) outputRef.getValue()).getReferenceElement()
						.getIdPart();
				// Get Diagnostic Report and associated Observations (using include)
				Bundle diagnosticReportBundle = client.search().forResource(DiagnosticReport.class)
						.where(new TokenClientParam("_id").exactly().code(openelisDiagnosticReportUuid))
						.include(DiagnosticReport.INCLUDE_RESULT).include(DiagnosticReport.INCLUDE_SUBJECT)
						.returnBundle(Bundle.class).execute();

				DiagnosticReport diagnosticReport = (DiagnosticReport) diagnosticReportBundle.getEntryFirstRep()
						.getResource();
				Coding diagnosticReportCode = diagnosticReport.getCode().getCodingFirstRep();
				if (diagnosticReportCode.getSystem().equals(LOINC_SYSTEM)) {
					List<Reference> results = new ArrayList<>();
					
					String labTestType = "";
					log.error("DIagnostic Report code is "+ diagnosticReportCode.getCode());
					FhirContext ctx = FhirContext.forR4();
					if (!allExistingLoincCodes.contains(diagnosticReportCode.getCode())) {
						if(diagnosticReportCode.getCode().equals("20447-9")){
							log.error("Registered lab test type as VL");
							labTestType = "VL";
						}
						else{
							log.error("Did not register lab test type as VL");
						}
						// save Observation
						for (Bundle.BundleEntryComponent entry : diagnosticReportBundle.getEntry()) {
							if (entry.hasResource()) {
								if (ResourceType.Observation.equals(entry.getResource().getResourceType())) {
									Observation newObs = (Observation) entry.getResource();
									newObs.setEncounter(encounterReference);
									newObs.setBasedOn(basedOn);
									newObs = observationService.create(newObs);
									Reference obsRef = new Reference();
									obsRef.setReference(
											ResourceType.Observation + "/" + newObs.getIdElement().getIdPart());
									results.add(obsRef);

									// for VL add additional Obs
									// if(labTestType.equals("VL")){
									// 	log.error("Found a VL Result so ... creating additional Observations");
									// 	//apply VL rules ... i.e. map to existing concepts
									// 	if(containsVLValueCodes(newObs.getCode().getCoding())){
									// 		Observation HIVTCVLDataObs = new Observation();
									// 		HIVTCVLDataObs.setId(UUID.randomUUID().toString());
									// 		HIVTCVLDataObs.setEncounter(encounterReference);
									// 		HIVTCVLDataObs.setSubject(newObs.getSubject());
									// 		HIVTCVLDataObs.setBasedOn(basedOn);
									// 		HIVTCVLDataObs.setStatus(newObs.getStatus());
									// 		HIVTCVLDataObs.setEffective(newObs.getEffectiveDateTimeType());
									// 		HIVTCVLDataObs.setCode(new CodeableConcept().addCoding(getDISACodingFor("HIVTC, Viral Load Data", "HIVTCVLDATA")));
											
									// 		Observation HIVTCVLDataAbnormalObs = new Observation();
									// 		HIVTCVLDataAbnormalObs.setId(UUID.randomUUID().toString());
									// 		HIVTCVLDataAbnormalObs.setEncounter(encounterReference);
									// 		HIVTCVLDataAbnormalObs.setSubject(newObs.getSubject());
									// 		HIVTCVLDataAbnormalObs.setBasedOn(basedOn);
									// 		HIVTCVLDataAbnormalObs.setStatus(newObs.getStatus());
									// 		HIVTCVLDataAbnormalObs.setEffective(newObs.getEffectiveDateTimeType());
									// 		HIVTCVLDataAbnormalObs.setCode(new CodeableConcept().addCoding(getDISACodingFor("Viral Load Abnormal", "HIVTCVLDATAAbnormal")));
									// 		//Internal Rules
									// 		BigDecimal VLValueNormalCutoff = new BigDecimal(999);
									// 		BigDecimal VLValueLow1 = new BigDecimal(20);
									// 		BigDecimal VLValueLow2 = new BigDecimal(30);
									// 		BigDecimal reportedVL = newObs.getValueQuantity().getValue();
									// 		if(reportedVL.compareTo(VLValueNormalCutoff) > 0){
									// 			//value is greater than 999 so it is Abnormal
									// 			// Set HIVTC, VL Data - Abonormal to true
									// 			HIVTCVLDataAbnormalObs.setValue(new BooleanType(true));
									// 		}
									// 		else{ 
									// 			// VL value is less or equal to 999
									// 			//if(val is < 20 or <30)
									// 			if((reportedVL.compareTo(VLValueLow1) == 0) && (newObs.getValueQuantity().hasComparator())){
									// 				if(newObs.getValueQuantity().getComparator().toCode().equals("<")){
									// 					//set HIVTC, Viral Load Result to <20
									// 				}	
									// 			}
									// 			else if ((reportedVL.compareTo(VLValueLow2) == 0) && (newObs.getValueQuantity().hasComparator())){
									// 				if(newObs.getValueQuantity().getComparator().toCode().equals("<")){
									// 					//set HIVTC, Viral Load Result to <30
									// 				}	
									// 			}
									// 			else if(reportedVL.compareTo(VLValueLow1) >= 0){
									// 				HIVTCVLDataAbnormalObs.setValue(new BooleanType(false));
									// 				//set HIVTC, Viral Load Result to >=20
									// 			}
									// 			else if(reportedVL.compareTo(VLValueLow2) >= 0){
									// 				HIVTCVLDataAbnormalObs.setValue(new BooleanType(false));
									// 				//set HIVTC, Viral Load Result to >=30
									// 			}
									// 		}
											
											
									// 		//save secondary obs
									// 		log.error("Abnormal Obs"+ctx.newJsonParser().encodeResourceToString(HIVTCVLDataAbnormalObs));
									// 		observationService.create(HIVTCVLDataAbnormalObs);
											
											

									// 		List<Reference> VLObsReferences = new ArrayList<Reference>();
									// 		Reference obsVLRef = new Reference();
									// 		obsVLRef.setReference(ResourceType.Observation + "/" + newObs.getIdElement().getIdPart()).setType("Observation");
									// 		VLObsReferences.add(obsVLRef);
									// 		Reference obsVLRefAbnormal = new Reference();
									// 		obsVLRefAbnormal.setReference(ResourceType.Observation + "/" + HIVTCVLDataAbnormalObs.getIdElement().getIdPart()).setType("Observation");
									// 		VLObsReferences.add(obsVLRefAbnormal);
									// 		HIVTCVLDataObs.setHasMember(VLObsReferences);
									// 		log.error("Data Obs"+ctx.newJsonParser().encodeResourceToString(HIVTCVLDataObs));
											
									// 		//StringType value = (StringType) (newObs.getValue().toString()+", "+HIVTCVLDataAbnormalObs.getValue().toString());
									// 		StringType val = new StringType(newObs.getValue().toString()+", "+HIVTCVLDataAbnormalObs.getValue());
									// 		//StringType val = new StringType("sample val");
									// 		HIVTCVLDataObs.setValue(val);
											

									// 		observationService.create(HIVTCVLDataObs);
											
									// 	}
									// 		// else if(newObs.getCode().getCoding().contains("Low val")){
									// 		// 	secondaryObs.setCode(null); //code for ldl
									// 		// }
									// }
								}
							}
						}
						diagnosticReport.setResult(results);
						diagnosticReport.setEncounter(encounterReference);
						diagnosticReport = diagnosticReportService.create(diagnosticReport);
						openmrsTask.addOutput().setValue(
								new Reference().setType(FhirConstants.DIAGNOSTIC_REPORT)
										.setReference(diagnosticReport.getIdElement().getIdPart()))
								.setType(diagnosticReport.getCode());
						taskOutPutUpdated = true;
					}
				}
			}
		}
		return taskOutPutUpdated;
	}

	private static Boolean containsVLValueCodes(List<Coding> list) {
		List <String> VLValueCodes = new ArrayList<String>();
		VLValueCodes.add("HIVVM");
		VLValueCodes.add("HIVVT");
		VLValueCodes.add("70241-5");
        for (Coding item : list) {
            if (VLValueCodes.contains(item.getCode())) {
                return true;
            }
        }
        return false;
    }

	private Coding getDISACodingFor(String name, String code) {
		String url = "http://health.gov.ls/laboratory-services";
        return new Coding(url, code, name);
    }

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}
}
