package auth;

import static java.util.concurrent.CompletableFuture.supplyAsync;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.persistence.EntityNotFoundException;
import models.Account;
import models.Applicant;
import play.libs.concurrent.HttpExecutionContext;
import repository.DatabaseExecutionContext;

/**
 * This is a "pure" wrapper of CiviFormProfileData. Since CiviFormProfileData is the serialized data
 * about a profile, this class should not store any data that should be serialized. It should
 * contain only server-local information, like execution contexts, database connections, etc.
 */
public class CiviFormProfile {
  private DatabaseExecutionContext dbContext;
  private HttpExecutionContext httpContext;
  private CiviFormProfileData profileData;

  @Inject
  public CiviFormProfile(
      DatabaseExecutionContext dbContext,
      HttpExecutionContext httpContext,
      CiviFormProfileData profileData) {
    this.dbContext = Preconditions.checkNotNull(dbContext);
    this.httpContext = Preconditions.checkNotNull(httpContext);
    this.profileData = Preconditions.checkNotNull(profileData);
  }

  public CompletableFuture<Applicant> getApplicant() {
    return this.getAccount()
        .thenApplyAsync(
            (a) ->
                a.getApplicants().stream()
                    .sorted(Comparator.comparing((applicant) -> applicant.getWhenCreated()))
                    .findFirst()
                    .orElseThrow(),
            httpContext.current());
  }

  public CompletableFuture<Account> getAccount() {
    return supplyAsync(
        () -> {
          Account account = new Account();
          account.id = Long.valueOf(this.profileData.getId());
          try {
            account.refresh();
          } catch (EntityNotFoundException e) {
            throw new AccountNonexistentException(e.getMessage());
          }
          return account;
        },
        dbContext);
  }

  public String getClientName() {
    return profileData.getClientName();
  }

  public Set<String> getRoles() {
    return profileData.getRoles();
  }

  public boolean isTrustedIntermediary() {
    return getRoles().contains(Roles.ROLE_TI.toString());
  }

  public boolean isCiviFormAdmin() {
    return profileData.getRoles().contains(Roles.ROLE_CIVIFORM_ADMIN.toString());
  }

  public boolean isProgramAdmin() {
    return this.getRoles().contains(Roles.ROLE_PROGRAM_ADMIN.name());
  }

  public String getId() {
    return profileData.getId();
  }

  public CompletableFuture<Void> setEmailAddress(String emailAddress) {
    return this.getAccount()
        .thenApplyAsync(
            a -> {
              String existingEmail = a.getEmailAddress();
              if (existingEmail == null || existingEmail.isEmpty()) {
                a.setEmailAddress(emailAddress);
                a.save();
              } else if (!existingEmail.equals(emailAddress)) {
                throw new ProfileMergeConflictException(
                    String.format(
                        "Profile already contains an email address: %s - which is different from"
                            + " the new email address %s.",
                        existingEmail, emailAddress));
              }
              return null;
            },
            dbContext);
  }

  public CompletableFuture<String> getEmailAddress() {
    return this.getAccount().thenApplyAsync(Account::getEmailAddress, httpContext.current());
  }

  public CiviFormProfileData getProfileData() {
    return this.profileData;
  }

  public CompletableFuture<Void> checkAuthorization(long applicantId) {
    return getAccount()
        .thenApplyAsync(
            account ->
                Stream.concat(
                        account
                            .getMemberOfGroup()
                            .flatMap(tiGroup -> Optional.of(tiGroup.getManagedAccounts().stream()))
                            .orElse(Stream.of()),
                        Stream.of(account))
                    .map(Account::ownedApplicantIds)
                    .reduce(
                        ImmutableList.of(),
                        (one, two) ->
                            new ImmutableList.Builder<Long>().addAll(one).addAll(two).build()))
        .thenApplyAsync(
            idList -> {
              if (!idList.contains(applicantId)) {
                throw new SecurityException(
                    String.format(
                        "Account %s is not authorized to access applicant %d",
                        getId(), applicantId));
              }
              return null;
            });
  }

  public CompletableFuture<Void> checkProgramAuthorization(String programName) {
    return this.getAccount()
        .thenApply(
            account -> {
              if (account.getAdministeredProgramNames().stream()
                  .anyMatch(program -> program.equals(programName))) {
                return null;
              }
              throw new SecurityException(
                  String.format(
                      "Account %s is not authorized to access program %s.", getId(), programName));
            });
  }
}
