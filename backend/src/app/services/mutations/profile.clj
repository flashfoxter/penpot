;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.services.mutations.profile
  (:require
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.db :as db]
   [app.emails :as emails]
   [app.media :as media]
   [app.media-storage :as mst]
   [app.http.session :as session]
   [app.services.mutations :as sm]
   [app.services.mutations.projects :as projects]
   [app.services.mutations.teams :as teams]
   [app.services.queries.profile :as profile]
   [app.services.tokens :as tokens]
   [app.services.mutations.verify-token :refer [process-token]]
   [app.tasks :as tasks]
   [app.util.blob :as blob]
   [app.util.storage :as ust]
   [app.util.time :as dt]
   [buddy.hashers :as hashers]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; --- Helpers & Specs

(s/def ::email ::us/email)
(s/def ::fullname ::us/not-empty-string)
(s/def ::lang ::us/not-empty-string)
(s/def ::path ::us/string)
(s/def ::profile-id ::us/uuid)
(s/def ::password ::us/not-empty-string)
(s/def ::old-password ::us/not-empty-string)
(s/def ::theme ::us/string)

;; --- Mutation: Register Profile

(declare check-profile-existence!)
(declare create-profile)
(declare create-profile-relations)
(declare email-domain-in-whitelist?)

(s/def ::token ::us/not-empty-string)
(s/def ::register-profile
  (s/keys :req-un [::email ::password ::fullname]
          :opt-un [::token]))

(sm/defmutation ::register-profile
  [{:keys [token] :as params}]
  (when-not (:registration-enabled cfg/config)
    (ex/raise :type :restriction
              :code :registration-disabled))

  (when-not (email-domain-in-whitelist? (:registration-domain-whitelist cfg/config)
                                        (:email params))
    (ex/raise :type :validation
              :code :email-domain-is-not-allowed))

  (db/with-atomic [conn db/pool]
    (check-profile-existence! conn params)
    (let [profile (->> (create-profile conn params)
                       (create-profile-relations conn))]

      (if token
        ;; If token comes in params, this is because the user comes
        ;; from team-invitation process; in this case we revalidate
        ;; the token and process the token claims again with the new
        ;; profile data.
        (let [claims (tokens/verify token {:iss :team-invitation})
              claims (assoc claims :member-id  (:id profile))
              params (assoc params :profile-id (:id profile))]
          (process-token conn params claims)

          ;; Automatically mark the created profile as active because
          ;; we already have the verification of email with the
          ;; team-invitation token.
          (db/update! conn :profile
                      {:is-active true}
                      {:id (:id profile)})

          ;; Return profile data and create http session for
          ;; automatically login the profile.
          (with-meta (assoc profile
                            :is-active true
                            :claims claims)
            {:transform-response
             (fn [request response]
               (let [uagent (get-in request [:headers "user-agent"])
                     id     (session/create (:id profile) uagent)]
                 (assoc response
                        :cookies (session/cookies id))))}))

        ;; If no token is provided, send a verification email
        (let [token (tokens/generate
                     {:iss :verify-email
                      :exp (dt/in-future "48h")
                      :profile-id (:id profile)
                      :email (:email profile)})]

          (emails/send! conn emails/register
                        {:to (:email profile)
                         :name (:fullname profile)
                         :token token})

          profile)))))


(defn email-domain-in-whitelist?
  "Returns true if email's domain is in the given whitelist or if given
  whitelist is an empty string."
  [whitelist email]
  (if (str/blank? whitelist)
    true
    (let [domains (str/split whitelist #",\s*")
          email-domain (second (str/split email #"@"))]
      (contains? (set domains) email-domain))))

(def ^:private sql:profile-existence
  "select exists (select * from profile
                   where email = ?
                     and deleted_at is null) as val")

(defn check-profile-existence!
  [conn {:keys [email] :as params}]
  (let [email  (str/lower email)
        result (db/exec-one! conn [sql:profile-existence email])]
    (when (:val result)
      (ex/raise :type :validation
                :code :email-already-exists))
    params))

(defn- derive-password
  [password]
  (hashers/derive password {:alg :bcrypt+sha512}))

(defn- verify-password
  [attempt password]
  (hashers/verify attempt password))

(defn- create-profile
  "Create the profile entry on the database with limited input
  filling all the other fields with defaults."
  [conn {:keys [id fullname email password demo?] :as params}]
  (let [id       (or id (uuid/next))
        demo?    (if (boolean? demo?) demo? false)
        active?  (if demo? true false)
        password (derive-password password)]
    (db/insert! conn :profile
                {:id id
                 :fullname fullname
                 :email (str/lower email)
                 :photo ""
                 :password password
                 :is-active active?
                 :is-demo demo?})))

(defn- create-profile-relations
  [conn profile]
  (let [team (teams/create-team conn {:profile-id (:id profile)
                                      :name "Default"
                                      :default? true})
        proj (projects/create-project conn {:profile-id (:id profile)
                                            :team-id (:id team)
                                            :name "Drafts"
                                            :default? true})]
    (teams/create-team-profile conn {:team-id (:id team)
                                     :profile-id (:id profile)})
    (projects/create-project-profile conn {:project-id (:id proj)
                                           :profile-id (:id profile)})

    (merge (profile/strip-private-attrs profile)
           {:default-team-id (:id team)
            :default-project-id (:id proj)})))

;; --- Mutation: Login

(s/def ::email ::us/email)
(s/def ::scope ::us/string)

(s/def ::login
  (s/keys :req-un [::email ::password]
          :opt-un [::scope]))

(sm/defmutation ::login
  [{:keys [email password scope] :as params}]
  (letfn [(check-password [profile password]
            (when (= (:password profile) "!")
              (ex/raise :type :validation
                        :code :account-without-password))
            (:valid (verify-password password (:password profile))))

          (validate-profile [profile]
            (when-not (:is-active profile)
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when-not profile
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when-not (check-password profile password)
              (ex/raise :type :validation
                        :code :wrong-credentials))
            profile)]

    (db/with-atomic [conn db/pool]
      (let [prof (-> (profile/retrieve-profile-data-by-email conn email)
                     (validate-profile)
                     (profile/strip-private-attrs))
            addt (profile/retrieve-additional-data conn (:id prof))]
        (merge prof addt)))))


;; --- Mutation: Register if not exists

(sm/defmutation ::login-or-register
  [{:keys [email fullname] :as params}]
  (letfn [(populate-additional-data [conn profile]
            (let [data (profile/retrieve-additional-data conn (:id profile))]
              (merge profile data)))

          (create-profile [conn {:keys [fullname email]}]
            (db/insert! conn :profile
                        {:id (uuid/next)
                         :fullname fullname
                         :email (str/lower email)
                         :is-active true
                         :photo ""
                         :password "!"
                         :is-demo false}))

          (register-profile [conn params]
            (->> (create-profile conn params)
                 (create-profile-relations conn)))]

    (db/with-atomic [conn db/pool]
      (let [profile (profile/retrieve-profile-data-by-email conn email)
            profile (if profile
                      (populate-additional-data conn profile)
                      (register-profile conn params))]
        (profile/strip-private-attrs profile)))))


;; --- Mutation: Update Profile (own)

(defn- update-profile
  [conn {:keys [id fullname lang theme] :as params}]
  (db/update! conn :profile
              {:fullname fullname
               :lang lang
               :theme theme}
              {:id id}))

(s/def ::update-profile
  (s/keys :req-un [::id ::fullname ::lang ::theme]))

(sm/defmutation ::update-profile
  [params]
  (db/with-atomic [conn db/pool]
    (update-profile conn params)
    nil))


;; --- Mutation: Update Password

(defn- validate-password!
  [conn {:keys [profile-id old-password] :as params}]
  (let [profile (profile/retrieve-profile-data conn profile-id)]
    (when-not (:valid (verify-password old-password (:password profile)))
      (ex/raise :type :validation
                :code :old-password-not-match))))

(s/def ::update-profile-password
  (s/keys :req-un [::profile-id ::password ::old-password]))

(sm/defmutation ::update-profile-password
  [{:keys [password profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (validate-password! conn params)
    (db/update! conn :profile
                {:password (derive-password password)}
                {:id profile-id})
    nil))


;; --- Mutation: Update Photo

(declare update-profile-photo)

(s/def ::file ::media/upload)
(s/def ::update-profile-photo
  (s/keys :req-un [::profile-id ::file]))

(sm/defmutation ::update-profile-photo
  [{:keys [profile-id file] :as params}]
  (media/validate-media-type (:content-type file))
  (db/with-atomic [conn db/pool]
    (let [profile (profile/retrieve-profile conn profile-id)
          _       (media/run {:cmd :info :input {:path (:tempfile file)
                                                 :mtype (:content-type file)}})
          photo   (teams/upload-photo conn params)]

      ;; Schedule deletion of old photo
      (when (and (string? (:photo profile))
                 (not (str/blank? (:photo profile))))
        (tasks/submit! conn {:name "remove-media"
                             :props {:path (:photo profile)}}))
      ;; Save new photo
      (update-profile-photo conn profile-id photo))))

(defn- update-profile-photo
  [conn profile-id path]
  (db/update! conn :profile
              {:photo (str path)}
              {:id profile-id})
  nil)

;; --- Mutation: Request Email Change

(s/def ::request-email-change
  (s/keys :req-un [::email]))

(sm/defmutation ::request-email-change
  [{:keys [profile-id email] :as params}]
  (db/with-atomic [conn db/pool]
    (let [email   (str/lower email)
          profile (db/get-by-id conn :profile profile-id)
          token   (tokens/generate
                   {:iss :change-email
                    :exp (dt/in-future "15m")
                    :profile-id profile-id
                    :email email})]

      (when (not= email (:email profile))
        (check-profile-existence! conn params))

      (emails/send! conn emails/change-email
                    {:to (:email profile)
                     :name (:fullname profile)
                     :pending-email email
                     :token token})
      nil)))

(defn select-profile-for-update
  [conn id]
  (db/get-by-id conn :profile id {:for-update true}))

;; --- Mutation: Request Profile Recovery

(s/def ::request-profile-recovery
  (s/keys :req-un [::email]))

(sm/defmutation ::request-profile-recovery
  [{:keys [email] :as params}]
  (letfn [(create-recovery-token [conn {:keys [id] :as profile}]
            (let [token (tokens/generate
                         {:iss :password-recovery
                          :exp (dt/in-future "15m")
                          :profile-id id})]
              (assoc profile :token token)))

          (send-email-notification [conn profile]
            (emails/send! conn emails/password-recovery
                          {:to (:email profile)
                           :token (:token profile)
                           :name (:fullname profile)}))]

    (db/with-atomic [conn db/pool]
      (some->> email
               (profile/retrieve-profile-data-by-email conn)
               (create-recovery-token conn)
               (send-email-notification conn))
      nil)))


;; --- Mutation: Recover Profile

(s/def ::token ::us/not-empty-string)
(s/def ::recover-profile
  (s/keys :req-un [::token ::password]))

(sm/defmutation ::recover-profile
  [{:keys [token password]}]
  (letfn [(validate-token [conn token]
            (let [tdata (tokens/verify token {:iss :password-recovery})]
              (:profile-id tdata)))

          (update-password [conn profile-id]
            (let [pwd (derive-password password)]
              (db/update! conn :profile {:password pwd} {:id profile-id})))]

    (db/with-atomic [conn db/pool]
      (->> (validate-token conn token)
           (update-password conn))
      nil)))


;; --- Mutation: Delete Profile

(declare check-teams-ownership!)
(declare mark-profile-as-deleted!)

(s/def ::delete-profile
  (s/keys :req-un [::profile-id]))

(sm/defmutation ::delete-profile
  [{:keys [profile-id] :as params}]
  (db/with-atomic [conn db/pool]
    (check-teams-ownership! conn profile-id)

    ;; Schedule a complete deletion of profile
    (tasks/submit! conn {:name "delete-profile"
                         :delay (dt/duration {:hours 48})
                         :props {:profile-id profile-id}})

    (db/update! conn :profile
                {:deleted-at (dt/now)}
                {:id profile-id})

    (with-meta {}
      {:transform-response
       (fn [request response]
         (some-> (session/extract-auth-token request)
                 (session/delete))
         (assoc response
                :cookies (session/cookies "" {:max-age -1})))})))

(def ^:private sql:teams-ownership-check
  "with teams as (
     select tpr.team_id as id
       from team_profile_rel as tpr
      where tpr.profile_id = ?
        and tpr.is_owner is true
   )
   select tpr.team_id,
          count(tpr.profile_id) as num_profiles
     from team_profile_rel as tpr
    where tpr.team_id in (select id from teams)
    group by tpr.team_id
   having count(tpr.profile_id) > 1")

(defn- check-teams-ownership!
  [conn profile-id]
  (let [rows (db/exec! conn [sql:teams-ownership-check profile-id])]
    (when-not (empty? rows)
      (ex/raise :type :validation
                :code :owner-teams-with-people
                :hint "The user need to transfer ownership of owned teams."
                :context {:teams (mapv :team-id rows)}))))
