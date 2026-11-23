package org.openmrs.module.labonfhir.api.model;

import org.openmrs.BaseOpenmrsData;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "failed_task")
public class FailedTask extends BaseOpenmrsData {

    @Id
	@GeneratedValue
	@Column(name = "id")
	private Integer id;

    @Column(name = "task_uuid", length = 255)
	private String taskUuid;

	@Column(name = "error", length = 255)
	private String error;
	
	@Column(name = "is_sent")
	private boolean isSent;

	public static final String TYPE_LAB = "LAB";

	public static final String TYPE_PRESCRIPTION = "PRESCRIPTION";

	/** LAB: taskUuid is a Task UUID. PRESCRIPTION: taskUuid is the UUID of the prescription's encounter. */
	@Column(name = "task_type", length = 32)
	private String taskType = TYPE_LAB;

    @Override
    public Integer getId() {
        return id;
    }
    @Override
    public void setId(Integer id) {
        this.id = id;
    }
    
    public String getError() {
        return error;
    }
    
    public void setError(String error) {
        this.error = error;
    }
    
    public boolean getIsSent() {
        return isSent;
    }
    
    public void setIsSent(boolean isSent) {
        this.isSent = isSent;
    }

    public String getTaskType() {
        return taskType == null ? TYPE_LAB : taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }
    
    public String getTaskUuid() {
        return taskUuid;
    }
    
    public void setTaskUuid(String taskUuid) {
        this.taskUuid = taskUuid;
    }
}
