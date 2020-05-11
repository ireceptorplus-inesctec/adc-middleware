package pt.inesctec.adcauthmiddleware.db.models;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.validation.constraints.NotNull;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
public class Rearrangement {
  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private Long id;

  @Column(unique = true, nullable = false)
  private String rearrangementId;

  @ManyToOne
  @OnDelete(action = OnDeleteAction.CASCADE)
  @NotNull
  private Repertoire repertoire;

  public Rearrangement() {}

  public Rearrangement(String rearrangementId, Repertoire repertoire) {
    this.rearrangementId = rearrangementId;
    this.repertoire = repertoire;
  }

  public String getRearrangementId() {
    return rearrangementId;
  }

  public Repertoire getRepertoire() {
    return repertoire;
  }

  @Override
  public String toString() {
    return String.format("{rearrangementId: %s}", rearrangementId);
  }
}
