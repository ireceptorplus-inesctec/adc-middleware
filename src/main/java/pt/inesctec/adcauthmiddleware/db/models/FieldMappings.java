package pt.inesctec.adcauthmiddleware.db.models;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

/**
 * Models the DB's study ID to UMA ID associations.
 */
@Entity
public class FieldMappings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}