package controllers

import javax.inject.Inject

import com.github.tototoshi.play2.json4s.native.Json4s
import models.Meter.autoSession
import models.{MeterReading, _}
import org.json4s.ext.JodaTimeSerializers
import org.json4s.{DefaultFormats, Extraction}
import play.api.data.Form
import play.api.data.Forms._
import play.api.mvc.{Action, Controller, Cookie}
import scalikejdbc._
import utils.Grouper

case class MeterReadingData(date: String,
                            value: String)

object MeterReadingData {
  def fromMeterReading(mr: MeterReading) = MeterReadingData(
    mr.date.toString,
    mr.value.toString)
}
case class MeterData(id:Int,
                     title: String,
                     unit: String)

object MeterData {
  def fromMeter(m: Meter) =
    MeterData(
      m.meterId,
      m.title,
      m.getUnit.description
    )
}

case class FlatData(flatId: Int,
                    flatNumber: String,
                    meters: Seq[MeterData])

object FlatData {
  def fromMap(map: Map[Flat, Seq[Meter]]): Seq[FlatData] = map.map {
    case (flat, meters) =>
      FlatData(
        flatId = flat.flatId,
        flatNumber = flat.flatNumber.toString,
        meters = meters.map(MeterData.fromMeter)
      )
  }.toSeq
}

case class ProfileData(name: String,
                       surname: String,
                       paternalName: String,
                       email: String,
                       isAdmin: Boolean,
                       flats: Seq[FlatData])

case class SignUpForm(name: String,
                      surname: String,
                      paternalName: String,
                      //                      address: String, // TODO for next version
                      email: String,
                      passwordHash: String)

case class SignInForm(email: String,
                      passwordHash: String)

/** Created by a.kiselev on 31/10/2016. */
class Dwellers @Inject() (json4j: Json4s) extends Controller {
  import json4j._
  implicit val formats = DefaultFormats ++ JodaTimeSerializers.all
  val signUpForm = Form(
    mapping(
      "name" -> text,
      "surname" -> text,
      "paternalName" -> text,
      //      "address" -> text, // TODO for next version
      "email" -> text,
      "passwordHash" -> text
    )(SignUpForm.apply)(SignUpForm.unapply)
  )
  val signInForm = Form(
    mapping(
      "email" -> text,
      "passwordHash" -> text
    )(SignInForm.apply)(SignInForm.unapply)
  )

  def findById(personId: Int) = Action {
    Person.find(personId)
      .map(Extraction.decompose)
      .map(Ok(_))
      .getOrElse(NotFound)
  }

  def isAdmin(personId: Int) = Action {
    Ok(Person.find(personId).exists(_.isAdmin).toString)
  }

  def signUp() =
    Action { implicit req =>
      signUpForm.bindFromRequest.fold(
        formWithErrors => BadRequest("Error: " + formWithErrors.toString), // TODO better error handling
        form => {
          val person = Person.create(
            name = form.name,
            surname = form.surname,
            paternalName = form.paternalName,
            email = form.email,
            passwordHash = form.passwordHash)
          Dweller.create(person.personId) // TODO deffer making person a dweller

          Ok(s"${person.personId}")
        }
      )
    }

  def signIn() = Action { implicit req =>
    import Person.p

    signInForm.bindFromRequest.fold(
      formWithErrors => BadRequest("Error"), // TODO better error handling
      form => {
        Person.findBy(sqls"${p.email} = ${form.email} and ${p.passwordHash} = ${form.passwordHash}")
          .map(_.personId)
          .map(id => Ok(s"$id")
            .withCookies(Cookie("person_id", id.toString)))
          .getOrElse(NotFound)
      }
    )
  }

  def listAll()(implicit session: DBSession = autoSession) =
    Action {
      import Dweller.d
      import Person.p

      val dwellers =
        sql"""
             select ${p.result.*} from ${Dweller as d}
               natural join ${Person as p}
        """.map(Person(p.resultName)).list().apply()

      Ok(Extraction.decompose(dwellers))
    }

  def getProfileData(personId: Int)(implicit session: DBSession = autoSession) = Action {
    val (d, p, f, m, df) = (Dweller.d, Person.p, Flat.f, Meter.m, DwellerLivesInFlat.df)

    Person.find(personId).map {
      case Person(_, name, surname, paternalName, _, email, _, isAdmin) =>
        val query =
          sql"""
          select ${d.result.*}, ${f.result.*}, ${m.result.*}
          from ${Dweller as d}
            natural join ${DwellerLivesInFlat as df}
            natural join ${Flat as f}
            natural join ${Meter as m}
          where ${d.personId} = ${personId}
          """.map(rs => {
            (Flat(f)(rs), Meter(m)(rs))
          }).list().apply()
        val flats = FlatData.fromMap(Grouper.groupToMap(query))

        Ok(Extraction.decompose(
          ProfileData(
            name = name,
            surname = surname,
            paternalName = paternalName,
            email = email,
            isAdmin = isAdmin,
            flats = flats
          )))
    }.getOrElse(NotFound)
  }
}
