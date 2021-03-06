package pt.inesctec.adcauthmiddleware.db.dto;

import pt.inesctec.adcauthmiddleware.db.models.AdcFields;

public class AdcFieldsDto {
    private long id;
    private String name;
    private long classId;

    public AdcFieldsDto(AdcFields adcFields) {
        this.id = adcFields.getId();
        this.name = adcFields.getName();
        this.classId = adcFields.getType().getId();
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getClassId() {
        return classId;
    }

    public void setClassId(long classId) {
        this.classId = classId;
    }
}
