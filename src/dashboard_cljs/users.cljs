(ns dashboard-cljs.users
  (:require [reagent.core :as r]
            [cljs.core.async :refer [put! sub chan]]
            [dashboard-cljs.cookies :as cookies]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.forms :refer [entity-save retrieve-entity
                                          edit-on-success edit-on-error]]
            [dashboard-cljs.orders :refer [user-cross-link-on-click]]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt
                                          unix-epoch->hrf markets
                                          json-string->clj pager-helper!
                                          integer->comma-sep-string
                                          parse-to-number? diff-message
                                          accessible-routes get-event-time now
                                          update-values select-toggle-key!
                                          subscription-id->name]]
            [dashboard-cljs.state :refer [users-state]]
            [dashboard-cljs.xhr :refer [retrieve-url xhrio-wrapper]]
            [dashboard-cljs.components :refer [DynamicTable
                                               RefreshButton KeyVal StarRating
                                               TablePager ConfirmationAlert
                                               FormGroup TextInput
                                               AlertSuccess
                                               SubmitDismissConfirmGroup
                                               TextAreaInput ViewHideButton
                                               TelephoneNumber Mailto
                                               GoogleMapLink Tab TabContent
                                               ProcessingIcon UserCrossLink
                                               ErrorComp]]
            [clojure.set :refer [subset?]]
            [clojure.string :as s]))

(def push-selected-users (r/atom (set nil)))

(def state users-state)

(def user-search-results (r/cursor state [:search-results :users]))

(datastore/sync-state! user-search-results
                       (sub
                        datastore/read-data-chan "user-search-results" (chan)))

(defn update-user-count
  []
  (retrieve-url
   (str base-url "users-count")
   "GET"
   {}
   (partial
    xhrio-wrapper
    (fn [response]
      (let [res (js->clj response :keywordize-keys true)]
        (reset! (r/cursor state [:users-count]) (integer->comma-sep-string
                                                 (:total (first res)))))))))

(defn update-members-count
  [saving?]
  (retrieve-url
   (str base-url "members-count")
   "GET"
   {}
   (partial
    xhrio-wrapper
    (fn [response]
      (let [res (js->clj response :keywordize-keys true)]
        (reset! (r/cursor state [:members-count]) (integer->comma-sep-string
                                                   (:total (first res))))
        (reset! saving? false))))))

(defonce
  users-count-result
  (update-user-count))

(defonce members-count-result
  (update-members-count (r/cursor state [:update-members-count-saving?])))


(defn displayed-user
  [user]
  (let [{:keys [referral_gallons]} user]
    (assoc user
           :referral_gallons
           (str referral_gallons)
           :referral-comment "")))

(defn user->server-req
  [user]
  (let [{:keys [referral_gallons]} user
        processed-ref-gallons (-> referral_gallons
                                  (clojure.string/replace #"," "")
                                  (js/Number))]
    (assoc user
           :referral_gallons
           (if (parse-to-number? processed-ref-gallons)
             processed-ref-gallons
             referral_gallons))))

(defn reset-edit-user!
  [edit-user current-user]
  (reset! edit-user
          (displayed-user @current-user)))

(defn users-count-panel
  [state]
  (let [saving? (r/cursor state [:update-members-count-saving?])
        refresh-fn (fn [saving?]
                     (reset! saving? true)
                     (update-user-count)
                     (update-members-count saving?))]
    (fn []
      [:div {:class "row"}
       [:div {:class "col-lg-12 col-xs -12"}
        [:div
         [:h3 {:style {:margin-top "0px"}}
          (str "Users (" @(r/cursor state [:users-count]) ") ")
          [:span {:style {:color "#5cb85c"}}
           (str  "Members (" @(r/cursor state [:members-count]) ") ")
           [RefreshButton {:refresh-fn (fn []
                                         (refresh-fn saving?))
                           :refreshing? saving?}]]]]]])))

(defn user-history-header
  "props
  {
  :sort-keyword   ; r/atom, keyword
  :sort-reversed? ; r/atom, boolean
  }"
  [props]
  (fn [props]
    [:thead
     [:tr
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Date"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Admin"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Adjustment"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}}
       "Comment"]]]))

(defn user-history-row
  []
  (fn [user-log]
    [:tr
     ;; Date
     [:td (unix-epoch->fmt (:timestamp user-log) "M/D/YYYY h:mm A")]
     ;; Admin
     [:td (:admin_name user-log)]
     ;; Gallon adjustment
     [:td (str (:previous_value user-log) " -> " (:new_value user-log))]
     ;; comment
     [:td (:comment user-log)]]))

(defn user-form
  "Form for editing a user"
  [user state]
  (let [edit-user (r/cursor state [:edit-user])
        current-user (r/cursor state [:current-user])
        retrieving? (r/cursor edit-user [:retrieving?])
        editing? (r/cursor edit-user [:editing?])
        confirming? (r/cursor state [:confirming-edit?])
        errors   (r/cursor edit-user [:errors])
        referral-gallons (r/cursor edit-user [:referral_gallons])
        comment (r/cursor edit-user [:referral_comment])
        alert-success (r/cursor state [:alert-success])
        diff-key-str {:referral_gallons "Referral Gallons"}
        diff-msg-gen (fn [edit current] (diff-message edit
                                                      (displayed-user current)
                                                      diff-key-str))]
    (fn [user]
      (let [default-card-info (if (empty? (:stripe_cards @edit-user))
                                nil
                                (->> (:stripe_cards @edit-user)
                                     json-string->clj
                                     (filter #(= (:stripe_default_card
                                                  @edit-user)
                                                 (:id %)))
                                     first))
            submit-on-click (fn [e]
                              (.preventDefault e)
                              (if @editing?
                                (if (every? nil?
                                            (diff-msg-gen @edit-user
                                                          @current-user))
                                  ;; there isn't a diff message, no changes
                                  ;; do nothing
                                  (reset! editing? (not @editing?))
                                  ;; there is a diff message, confirm changes
                                  (reset! confirming? true))
                                (do
                                  ;; get rid of alert-success
                                  (reset! alert-success "")
                                  (reset! editing? (not @editing?)))))
            dismiss-fn (fn [e]
                         ;; reset any errors
                         (reset! errors nil)
                         ;; no longer editing
                         (reset! editing? false)
                         ;; reset current user
                         (reset-edit-user! edit-user current-user)
                         ;; reset confirming
                         (reset! confirming? false))]
        [:form {:class "form-horizontal"}
         ;; email
         [KeyVal "ID" (:id @user)]
         [KeyVal "Email" [Mailto (:email @user)]]
         ;; phone number
         [KeyVal "Phone" [TelephoneNumber (:phone_number @user)]]
         ;; date started
         [KeyVal "Registered" (unix-epoch->fmt
                               (:timestamp_created @user)
                               "M/D/YYYY")]
         ;; last active (last ping)
         (when-not (nil? (:last_active @user))
           [KeyVal "Last Active" (unix-epoch->fmt
                                  (:last_active @user)
                                  "M/D/YYYY")])
         ;; default card
         (when (not (nil? default-card-info))
           [KeyVal "Default Card"
            (str
             (:brand default-card-info)
             " "
             (:last4 default-card-info)
             " "
             (when (not (empty? (:exp_month default-card-info)))
               (:exp_month default-card-info)
               "/"
               (:exp_year default-card-info)))])
         ;; referral code
         [KeyVal "Referral Code" (:referral_code @user)]
         ;; is this user a courier?
         [KeyVal "Courier" (if (:is_courier @user)
                             "Yes"
                             "No")]
         ;; Referral Gallons
         (if @editing?
           [:div
            [FormGroup {:label "Credit Gallons"
                        :label-for "referral gallons"
                        :errors (:referral_gallons @errors)}
             [TextInput {:value @referral-gallons
                         :default-value @referral-gallons
                         :on-change #(reset!
                                      referral-gallons
                                      (-> %
                                          (aget "target")
                                          (aget "value")))}]]
            [FormGroup {:label "Reason for Changing Credit Gallons"
                        :label-for "referral gallons comment"}
             [TextAreaInput {:value @comment
                             :rows 2
                             :cols 50
                             :on-change #(reset!
                                          comment
                                          (-> %
                                              (aget "target")
                                              (aget "value")))}]]
            [:br]]
           [KeyVal "Credit Gallons" (:referral_gallons @user)])
         (when (subset? #{{:uri "/user"
                           :method "PUT"}}
                        @accessible-routes)
           [SubmitDismissConfirmGroup
            {:confirming? confirming?
             :editing? editing?
             :retrieving? retrieving?
             :submit-fn submit-on-click
             :dismiss-fn dismiss-fn}])
         (when (subset? #{{:uri "/user"
                           :method "PUT"}}
                        @accessible-routes)
           (if (and @confirming?
                    (not-every? nil?
                                (diff-msg-gen @edit-user @current-user)))
             [ConfirmationAlert
              {:confirmation-message
               (fn []
                 [:div (str "Do you want to make the following changes to "
                            (:name @current-user) "?")
                  (map (fn [el]
                         ^{:key el}
                         [:h4 el])
                       (diff-msg-gen @edit-user @current-user))])
               :cancel-on-click dismiss-fn
               :confirm-on-click
               (fn [_]
                 (entity-save
                  (user->server-req @edit-user)
                  "user"
                  "PUT"
                  retrieving?
                  (edit-on-success "user" edit-user current-user
                                   alert-success
                                   :aux-fn
                                   #(reset! confirming? false)
                                   :channel-topic "user-search-results"
                                   )
                  (edit-on-error edit-user
                                 :aux-fn
                                 #(reset! confirming? false))))
               :retrieving? retrieving?}]
             (reset! confirming? false)))
         ;; success alert
         (when-not (empty? @alert-success)
           [AlertSuccess {:message @alert-success
                          :dismiss #(reset! alert-success "")}])]))))

(defn get-user-orders
  "Retrieve orders for user-id and insert them into the datastore"
  [user-id retrieving?]
  (reset! retrieving? true)
  (retrieve-url
   (str base-url "users/orders/"
        user-id)
   "GET"
   {}
   (partial
    xhrio-wrapper
    (fn [response]
      (let [orders (js->clj
                    response
                    :keywordize-keys true)]
        (reset! retrieving? false)
        (put! datastore/modify-data-chan
              {:topic "orders"
               :data orders}))))))

(defn current-user-change!
  "Whenever the current user changes, do some work"
  [current-user]
  (when-not (nil? @current-user)
    (let [retrieving? (r/cursor state [:user-orders-retrieving?])]
      (get-user-orders (:id @current-user) retrieving?))))

(defn user-push-notification
  "A component for sending push notifications to users"
  [user]
  (let [default-state {:approved? false
                       :confirming? false
                       :retrieving? false
                       :message (str)
                       :alert-success (str)
                       :alert-error (str)}
        state (r/atom default-state)
        approved?      (r/cursor state [:approve?])
        confirming?    (r/cursor state [:confirming?])
        retrieving?    (r/cursor state [:retrieving?])
        message        (r/cursor state [:message])
        alert-success  (r/cursor state [:alert-success])
        alert-error    (r/cursor state [:alert-error])
        confirm-on-click (fn [user e]
                           (let [{:keys [id name]} user]
                             (reset! retrieving? true)
                             (retrieve-url
                              (str base-url "send-push-to-user")
                              "POST"
                              (js/JSON.stringify
                               (clj->js {:message @message
                                         :user-id id}))
                              (partial
                               xhrio-wrapper
                               (fn [response]
                                 (reset! retrieving? false)
                                 (let [success? (:success
                                                 (js->clj response
                                                          :keywordize-keys
                                                          true))]
                                   (when success?
                                     ;; confirm message was sent
                                     (reset! alert-success
                                             (str "Pushed the message '"
                                                  @message "' to "
                                                  name
                                                  "!")))
                                   (when (not success?)
                                     (reset!
                                      alert-error
                                      (str "Something went wrong."
                                           " Push notifications may or may"
                                           " not have been sent. Wait until"
                                           " sure before trying again."))))
                                 (reset! confirming? false)
                                 (reset! message ""))))))
        confirmation-message  (fn [username]
                                (fn []
                                  [:div
                                   "Are you sure you want to push the message '"
                                   [:span [:strong @message]]
                                   "' to "
                                   username "?"
                                   ]))]
    (r/create-class
     {:component-will-receive-props
      (fn [this]
        (reset! state default-state))
      :reagent-render
      (fn [user]
        (let []
          [:div {:class "panel panel-default"}
           [:div {:class "panel-body"}
            [:div [:h4
                   (str "Send Push Notification to " (:name user))]]
            (if @confirming?
              ;; confirmation
              [ConfirmationAlert
               {:cancel-on-click (fn [e]
                                   (reset! confirming? false)
                                   (reset! message ""))
                :confirm-on-click (partial confirm-on-click user)
                :confirmation-message (confirmation-message (:name user))
                :retrieving? retrieving?}]
              ;; Message form
              [:form
               [:div {:class "form-group"}
                [:input {:type "text"
                         :defaultValue ""
                         :class "form-control"
                         :placeholder "Message"
                         :on-change (fn [e]
                                      (reset! message (-> e
                                                          (aget "target")
                                                          (aget "value")))
                                      (reset! alert-error "")
                                      (reset! alert-success ""))}]]
               [:button {:type "submit"
                         :class "btn btn-default"
                         :on-click (fn [e]
                                     (.preventDefault e)
                                     (when (not (empty? @message))
                                       (reset! confirming? true)))
                         :disabled (s/blank? @message)}
                "Send Notification"]])
            ;; alert message
            (when (not (empty? @alert-success))
              [:div {:class "alert alert-success alert-dismissible"}
               [:button {:type "button"
                         :class "close"
                         :aria-label "Close"}
                [:i {:class "fa fa-times"
                     :on-click #(reset! alert-success "")}]]
               [:strong @alert-success]])
            ;; alert error
            (when (not (empty? @alert-error))
              [:div {:class "alert alert-danger"}
               @alert-error])]]))})))

(defn UserNote
  "A component for displaying a user note"
  [{:keys [note current-user]}]
  (let [edit-note (r/atom {:retrieving? false
                           :errors nil
                           :comment nil})
        retrieving? (r/atom false)
        editing-note? (r/atom false)
        alert-success (r/atom "")
        edit-comment (r/cursor edit-note [:comment])
        aux-fn (fn [_]
                 (reset! editing-note? (not @editing-note?))
                 (reset! retrieving? false))
        cookie-admin-id (cookies/get-cookie "user-id")]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (reset! edit-comment (:comment note)))
      :reagent-render
      (fn [{:keys [note current-user]}]
        (let [{:keys [admin_email admin_id timestamp comment]} note]
          [:div
           (when-not @editing-note?
             [:div
              [:h4 comment]
              [:h5 (str "- " admin_email) ", "
               (unix-epoch->fmt timestamp "M/D/YYYY h:mm a")]])
           (when @editing-note?
             [:div [FormGroup {:label "Notes"}
                    [TextAreaInput {:value @edit-comment
                                    :rows 2
                                    :on-change #(reset!
                                                 edit-comment
                                                 (-> %
                                                     (aget "target")
                                                     (aget "value")))}]]])
           (when (= admin_id cookie-admin-id)
             [:h5
              (if-not @editing-note?
                [:a {:on-click (fn [e]
                                 (.preventDefault e)
                                 (reset! editing-note? true))}
                 "Edit"]
                [:a {:on-click
                     (fn [e]
                       (.preventDefault e)
                       (reset! editing-note? false)
                       ;; check to see if the note changed
                       (when (not= edit-comment (:comment note))
                         ;; the note changed, now replace the current
                         ;; user's note comment with the new one
                         (let [removed-note-admin-event-log
                               (filter #(not= (:timestamp %)
                                              timestamp)
                                       (:admin_event_log @current-user))
                               new-admin-event-log
                               (conj removed-note-admin-event-log
                                     (assoc note
                                            :comment @edit-comment))]
                           ;;(.log js/console (clj->js new-admin-event-log))
                           (.log js/console (clj->js removed-note-admin-event-log))
                           ;; upload this to the server
                           ;; (swap! current-user #(assoc %
                           ;;                             :admin_event_log
                           ;;                             new-admin-event-log))
                           (entity-save
                            (clj->js {:id (:id @current-user)
                                      :user_note (assoc note :comment @edit-comment)})
                            "user"
                            "PUT"
                            retrieving?
                            (edit-on-success "user" (r/atom {}) (r/atom {}) (r/atom {})
                                             :channel-topic "user-search-results")
                            #(.log js/console "some unknown error occured"))
                           ))
                       
                       )}
                 "Save"])
              " | "
              [:a "Delete"]])]))})))

(defn courier-conversion
  "Convert a user to a courier"
  [user]
  (let [default-state {:confirming? false
                       :editing? false
                       :retrieving? false
                       :alert-error ""}
        state (r/atom default-state)
        confirming? (r/cursor state [:confirming?])
        editing?     (r/cursor state [:editing?])
        retrieving? (r/cursor state [:retrieving?])
        alert-success (r/cursor users-state [:alert-success])
        alert-error   (r/cursor state [:alert-error])
        confirm-on-click  (fn [user e]
                            (let [{:keys [id name]} user]
                              (reset! retrieving? true)
                              (retrieve-url
                               (str base-url "users/convert-to-courier")
                               "PUT"
                               (js/JSON.stringify
                                (clj->js {:user {:id id}}))
                               (partial
                                xhrio-wrapper
                                (fn [response]
                                  (reset! retrieving? false)
                                  (let [response (js->clj response
                                                          :keywordize-keys
                                                          true)
                                        success? (:success response)]
                                    (when success?
                                      ;; confirm message was sent
                                      (reset! alert-success
                                              (str
                                               "Successfully converted '"
                                               name "' to a courier! You can "
                                               "now go to the Couriers tab and "
                                               "assign zones to this courier."))
                                      (retrieve-entity
                                       "user"
                                       id
                                       (fn [user]
                                         (put! datastore/modify-data-chan
                                               {:topic "user-search-results"
                                                :data user})
                                         (reset! (r/cursor users-state
                                                           [:current-user])
                                                 (first user))
                                         (datastore/sync-couriers!))))
                                    (when (not success?)
                                      (reset!
                                       alert-error
                                       (str "Error: " (:message response)))))
                                  (reset! confirming? false))))))
        confirmation-message  (fn [username]
                                (fn []
                                  [:div
                                   "Are you sure you want to convert '"
                                   [:span [:strong username]]
                                   "' to a courier?"
                                   [:br]
                                   [:span [:strong " Warning: This can not be "
                                           "undone from the dashboard!"]]
                                   [:br]
                                   " Once converted, "
                                   "this account will not be able to place "
                                   "orders and will only be able to accept "
                                   "orders for delivery."
                                   [:br]
                                   " This action "
                                   "can only be reversed manually by an admin!"]
                                  ))]
    (r/create-class
     {:component-will-receive-props
      (fn [this]
        (reset! state default-state))
      :reagent-render
      (fn [user]
        [:div
         (cond (not @confirming?)
               [:button {:type "button"
                         :class "btn btn-default"
                         :on-click (fn [e]
                                     (.preventDefault e)
                                     (reset! confirming? true)
                                     (reset! alert-success "")
                                     (reset! alert-error ""))}
                "Convert to Courier"]
               @confirming?
               [ConfirmationAlert
                {:cancel-on-click (fn [e]
                                    (reset! confirming? false))
                 :confirm-on-click (partial confirm-on-click user)
                 :confirmation-message (confirmation-message (:name user))
                 :retrieving? retrieving?}])
         ;; alert message
         (when (not (empty? @alert-success))
           [AlertSuccess
            {:message @alert-success
             :dismiss #(reset! alert-success "")}])
         ;; alert error
         (when (not (empty? @alert-error))
           [ErrorComp {:error-message @alert-error
                       :dismiss-fn #(reset! alert-error "")}])])})))

(defn user-panel
  "Display detailed and editable fields for an user. current-user is an
  r/atom"
  [current-user state]
  (let [sort-keyword (r/atom :target_time_start)
        sort-reversed? (r/atom false)
        current-page (r/cursor state [:user-orders-current-page])
        page-size 5
        edit-user    (r/cursor state [:edit-user])
        view-log?    (r/cursor state [:view-log?])
        toggle       (r/atom {})
        retrieving? (r/cursor state [:user-orders-retrieving?])
        orders-view-toggle? (r/cursor toggle [:orders-view])
        push-view-toggle?   (r/cursor toggle [:push-view])
        convert-courier-view? (r/cursor toggle [:convert-courier-view])]
    (fn [current-user]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            
            orders
            ;; filter out the orders to only those assigned
            ;; to the user
            (->> @datastore/orders
                 (filter (fn [order]
                           (= (:id @current-user)
                              (:user_id order)))))
            sorted-orders (->> orders
                               sort-fn
                               (partition-all page-size))
            paginated-orders (pager-helper! sorted-orders current-page)
            most-recent-order (->> orders
                                   (sort-by :target_time_start)
                                   first)
            current-user-update @(r/track current-user-change! current-user)
            ;; admin-event-log (:admin_event_log @current-user)
            ;; user-notes (->> admin-event-log
            ;;                 (filter #(= (:action %) "user_notes"))
            ;;                 (sort-by :timestamp))
            ]
        ;; edit-user should correspond to current-user
        (when-not (:editing? @edit-user)
          (reset! edit-user (assoc @edit-user
                                   :last_active (:target_time_start
                                                 most-recent-order))))
        ;; Make sure that orders view is not selected when a user has no orders
        (when (and (<= (count paginated-orders)
                       0)
                   @orders-view-toggle?)
          (select-toggle-key! toggle :info-view))
        ;; Make sure that push-view is not selected when a user has push
        ;; notifications turned off
        (when (and (s/blank? (:arn_endpoint @current-user))
                   @push-view-toggle?)
          (select-toggle-key! toggle :info-view))
        ;; Make sure that courier conversion is not selected when a user
        ;; is already a courier
        (when (and (:is_courier @current-user)
                   @convert-courier-view?)
          (select-toggle-key! toggle :info-view))
        ;; If the user is not qualified to be a courier
        ;; reset the toggle key
        (when (and (or (> (:orders_count @current-user) 0)
                       (:is_courier @current-user))
                   @convert-courier-view?)
          (select-toggle-key! toggle :info-view))
        [:div {:class "panel-body"}
         ;; populate the current user with additional information
         [:div {:class "row"}
          [:div {:class "col-xs-12 col-lg-12"}
           [:div [:h3
                  (:name @current-user)
                  (when-not (= 0 (:subscription_id @current-user))
                    [:span {:style {:color "#5cb85c"
                                    :font-size "0.7em !important"}}
                     (str " "
                          (subscription-id->name
                           (:subscription_id @current-user))
                          " Plan")])]]]]
         [:div {:class "row"}
          [:div {:class "col-xs-12 col-lg-12"}
           ;; users info tab navigation
           [:ul {:class "nav nav-tabs"}
            [Tab {:default? true
                  :toggle-key :info-view
                  :toggle toggle}
             "Info"]
            (when (> (count paginated-orders)
                     0)
              [Tab {:default? false
                    :toggle-key :orders-view
                    :toggle toggle}
               (str "Orders (" (count orders) ")")])
            (when (and (not (s/blank? (:arn_endpoint @current-user)))
                       (subset? #{{:uri "/send-push-to-user"
                                   :method "POST"}}
                                @accessible-routes))
              [Tab {:default? false
                    :toggle-key :push-view
                    :toggle toggle}
               "Push Notification"])
            (when (and (not (:is_courier @current-user ))
                       (< (:orders_count @current-user) 1)
                       (subset? #{{:uri "/users/convert-to-courier"
                                   :method "PUT"}}
                                @accessible-routes))
              [Tab {:default? false
                    :toggle-key :convert-courier-view
                    :toggle toggle}
               "Courier Conversion"])]]]
         ;; main display panel
         [:div {:class "tab-content"}
          [TabContent {:toggle (r/cursor toggle [:info-view])}
           [:div {:class "row"}
            [:div {:class "col-lg-3 col-xs-12"}
             [user-form current-user state]]
            ;; (when-not (empty? user-notes)
            ;;   [:div {:class "col-lg-9 col-xs-12"}
            ;;    (doall (map (fn [note]
            ;;                  ^{:key (:timestamp note)}
            ;;                  [UserNote {:note note
            ;;                             :current-user current-user}])
            ;;                user-notes))])
            ]]
          [TabContent {:toggle (r/cursor toggle [:push-view])}
           [:div {:class "row"}
            [:div {:class "col-lg-6 col-xs-12"}
             [user-push-notification @current-user]]]]
          ;; below is for showing user logs,
          ;; implemented, but not used yet
          ;; [:br]
          ;; [ViewHideButton {:class "btn btn-sm btn-default"
          ;;                  :view-content "View Logs"
          ;;                  :hide-content "Hide Logs"
          ;;                  :on-click #(swap! view-log? not)
          ;;                  :view? view-log?}]
          ;; (when @view-log?
          ;;   [:div {:class "table-responsive"
          ;;          :style (if @view-log?
          ;;                   {}
          ;;                   {:display "none"})}
          ;;    [StaticTable
          ;;     {:table-header [user-history-header
          ;;                     {;;:sort-keyword sort-keyword-logs
          ;;                      ;;:sort-reversed? sort-reversed-logs?
          ;;                      }]
          ;;      :table-row (user-history-row)}
          ;;     (sort-by :timestamp (:admin_event_log @current-user))]])
          [TabContent
           {:toggle (r/cursor toggle [:orders-view])}
           [:div {:class "row"}
            [:div {:class "col-lg-12 col-xs-12"}
             [:div {:style {:margin-top "1em"}}
              [RefreshButton {:refresh-fn (fn [refreshing?]
                                            (get-user-orders (:id @current-user)
                                                             refreshing?))
                              :refreshing? retrieving?}]]
             ;; Table of orders for current user
             [:div {:class "table-responsive"
                    :style (when-not (> (count paginated-orders)
                                        0)
                             {:display "none"})}
              [DynamicTable
               {:current-item (r/atom {})
                :tr-props-fn (constantly true)
                :sort-keyword sort-keyword
                :sort-reversed? sort-reversed?
                :table-vecs
                [["Status" :status :status]
                 ["Courier" :courier_name :courier_name]
                 ["Placed" :target_time_start
                  #(unix-epoch->hrf (:target_time_start %))]
                 ["Deadline" :target_time_end
                  (fn [order]
                    [:span
                     {:style
                      (when-not (contains?
                                 #{"complete" "cancelled"}
                                 (:status order))
                        (when (< (- (:target_time_end order)
                                    (now))
                                 (* 60 60))
                          {:color "#d9534f"}))}
                     (unix-epoch->hrf (:target_time_end order))
                     (when (:tire_pressure_check order)
                       ;; http://www.flaticon.com/free-icon/car-wheel_75660#term=wheel&page=1&position=34
                       [:img
                        {:src
                         (str base-url "/images/car-wheel.png")
                         :alt "tire-check"}])])]
                 ["Completed" (fn [order]
                                (cond (contains? #{"cancelled"}
                                                 (:status order))
                                      "Cancelled"
                                      (contains? #{"complete"}
                                                 (:status order))
                                      (unix-epoch->hrf
                                       (get-event-time (:event_log order)
                                                       "complete"))
                                      :else "In-Progress"))
                  (fn [order]
                    [:span
                     (when (contains? #{"complete"}
                                      (:status order))
                       (let [completed-time
                             (get-event-time (:event_log order)
                                             "complete")]
                         [:span {:style
                                 (when
                                     (> completed-time
                                        (:target_time_end order))
                                   {:color "#d9534f"})}
                          (unix-epoch->hrf completed-time)]))
                     (when (contains? #{"cancelled"}
                                      (:status order))
                       "Cancelled")
                     (when-not
                         (contains? #{"complete" "cancelled"}
                                    (:status order))
                       "In-Progress")])]
                 ["Order Address" :address_street
                  (fn [order]
                    [GoogleMapLink
                     (str (:address_street order)
                          ", " (:address_zip order))
                     (:lat order) (:lng order)])]
                 ["Courier Rating" :number_rating
                  (fn [order]
                    (let [number-rating (:number_rating order)]
                      (when number-rating
                        [StarRating number-rating])))]]}
               paginated-orders]]
             [:div {:style (when-not (> (count paginated-orders)
                                        0)
                             {:display "none"})}
              [TablePager
               {:total-pages (count sorted-orders)
                :current-page current-page}]]]]]
          [TabContent
           {:toggle (r/cursor toggle [:convert-courier-view])}
           [:div {:class "row"}
            [:div {:class "col-lg-12 col-xs-12"}
             [:div {:style {:margin-top "1em"}}
              [courier-conversion @current-user]]]]]]]))))

(defn search-users-results-panel
  "Display a table of selectable users with an indivdual user panel
  for the selected user"
  [users state]
  (let [current-user (r/cursor state [:current-user])
        edit-user    (r/cursor state [:edit-user])
        sort-keyword (r/atom :timestamp_created)
        sort-reversed? (r/atom false)
        current-page (r/atom 1)
        recent-search-term (r/cursor state [:recent-search-term])
        page-size 5]
    (fn [users]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            sorted-users (fn []
                           (->> users
                                sort-fn
                                (partition-all page-size)))
            paginated-users (fn []
                              (-> (sorted-users)
                                  (nth (- @current-page 1)
                                       '())))
            table-pager-on-click (fn []
                                   (let [first-user (first (paginated-users))]
                                     (reset! current-user first-user)))]
        (when (nil? @current-user)
          (table-pager-on-click))
        (reset-edit-user! edit-user current-user)
        ;; set the edit-user values to match those of current-user
        [:div {:class "panel panel-default"}
         [user-panel current-user state]
         [:h4 "Users matching - \""
          [:strong {:style {:white-space "pre"}}
           @recent-search-term] "\""]
         [:div {:class "panel"
                :style {:margin-top "15px"}}
          [:div {:class "table-responsive"}
           [DynamicTable
            {:current-item current-user
             :tr-props-fn (fn [user current-user]
                            (let [user-orders
                                  (fn [user]
                                    (->> @datastore/orders
                                         (filter (fn [order]
                                                   (= (:id user)
                                                      (:user_id order))))))]
                              {:class (when (= (:id user)
                                               (:id @current-user))
                                        "active")
                               :on-click (fn [_]
                                           (reset! current-user user)
                                           (reset!
                                            (r/cursor state
                                                      [:alert-success]) "")
                                           (when (<= (count (user-orders user))
                                                     0)
                                             (select-toggle-key!
                                              (r/cursor state
                                                        [:tab-content-toggle])
                                              :info-view))
                                           (reset!
                                            (r/cursor
                                             state
                                             [:user-orders-current-page]) 1))}))
             :sort-keyword sort-keyword
             :sort-reversed? sort-reversed?
             :table-vecs [["Name" :name
                           (fn [user]
                             [UserCrossLink
                              {:on-click
                               (fn [] (user-cross-link-on-click (:id user)))}
                              [:span
                               {:style (when-not (= 0 (:subscription_id user))
                                         {:color "#5cb85c"})}  (:name user)]])]
                          ["Market" (fn [user]
                                      (->
                                       (->> @datastore/orders
                                            (filter (fn [order]
                                                      (= (:id user)
                                                         (:user_id order)))))
                                       first
                                       :zone
                                       (quot 50)
                                       markets))
                           (fn [user]
                             (->
                              (->> @datastore/orders
                                   (filter (fn [order]
                                             (= (:id user)
                                                (:user_id order)))))
                              first
                              :zone
                              (quot 50)
                              markets))]
                          ["Orders" :orders_count :orders_count]
                          ["Email" :email (fn [user]
                                            [Mailto (:email user)])]
                          ["Phone" :phone_number
                           (fn [user]
                             [TelephoneNumber (:phone_number user)])]
                          ["Card?" #(if (s/blank? (:stripe_default_card %))
                                      "No"
                                      "Yes")
                           #(if (s/blank? (:stripe_default_card %))
                              "No"
                              "Yes")]
                          ["Push?"
                           #(if (s/blank? (:arn_endpoint %))
                              "No"
                              "Yes")
                           #(if (s/blank? (:arn_endpoint %))
                              "No"
                              "Yes")]
                          ["OS" :os :os]
                          ["Version" :app_version :app_version]
                          ["Joined"
                           :timestamp_created
                           #(unix-epoch->fmt
                             (:timestamp_created %) "M/D/YYYY")]]}
            (paginated-users)]]]
         [TablePager
          {:total-pages (count (sorted-users))
           :current-page current-page
           :on-click table-pager-on-click}]]))))


(defn search-bar
  [state]
  (let [retrieving?        (r/cursor state [:search-retrieving?])
        search-results     (r/cursor state [:search-results])
        recent-search-term (r/cursor state [:recent-search-term])
        search-term        (r/cursor state [:search-term])
        retrieve-results (fn [search-term]
                           (retrieve-url
                            (str base-url "search")
                            "POST"
                            (js/JSON.stringify (clj->js {:term search-term}))
                            (partial
                             xhrio-wrapper
                             (fn [r]
                               (let [response (js->clj
                                               r :keywordize-keys true)]
                                 (reset! retrieving? false)
                                 (reset! recent-search-term search-term)
                                 (reset! search-results response))))))]
    (fn []
      [:div {:class "row"}
       [:div {:class "col-lg-6 col-xs-12"}
        [:form {:role "users-search"}
         [:div {:class "input-group"}
          [:input {:type "text"
                   :class "form-control"
                   :placeholder "Search Users"
                   :on-change (fn [e]
                                (reset! search-term
                                        (-> e
                                            (aget "target")
                                            (aget "value"))))
                   :value @search-term}]
          [:div {:class "input-group-btn"}
           [:button {:class "btn btn-default"
                     :type "submit"
                     :on-click (fn [e]
                                 (.preventDefault e)
                                 (when-not (s/blank? @search-term)
                                   (reset! retrieving? true)
                                   (retrieve-results @search-term)
                                   (reset! (r/cursor state [:current-user]) nil)
                                   (reset! (r/cursor state [:current-order]) nil)))
                     }
            [:i {:class "fa fa-search"}]]]]]]])))


(defn search-results
  "Display search results"
  [state]
  (fn []
    (let [search-term (r/cursor state [:search-term])
          retrieving? (r/cursor state [:search-retrieving?])
          recent-search-term (r/cursor state [:recent-search-term])
          search-results (r/cursor state [:search-results])
          users-search-results (r/cursor search-results [:users])]
      [:div
       (when @retrieving?
         (.scrollTo js/window 0 0)
         [:h4 "Retrieving results for \""
          [:strong
           {:style {:white-space "pre"}}
           @search-term]
          "\" "
          [ProcessingIcon]])
       (when-not (nil? (and @search-term @recent-search-term))
         [:div {:class "row" :id "search-results"}
          [:div {:class "col-lg-12 col-lg-12"}
           (when-not @retrieving?
             [:div
              (when (and (empty? @users-search-results)
                         (not (s/blank? @recent-search-term))
                         (not @retrieving?))
                [:div [:h4 "Your search - \""
                       [:strong {:style {:white-space "pre"}}
                        @recent-search-term]
                       \"" - did not match any users."]])
              (when-not (empty? @users-search-results)
                [:div
                 [search-users-results-panel @users-search-results state]])])]])])))

(defn cross-link-result
  "Retrieve and display user-id when clicked from an internal cross-link"
  [state]
  (fn []
    (let [search-term (r/cursor state [:search-term])
          recent-search-term (r/cursor state [:recent-search-term])
          current-user (r/cursor state [:current-user])
          retrieving? (r/cursor state [:cross-link-retrieving?])
          ]
      [:div
       (when @retrieving?
         [:h4  "Retrieving user information "
          [:i {:class "fa fa-spinner fa-pulse"
               :style {:color "black"}}]])
       (when (nil? (and @search-term @recent-search-term))
         (when-not (nil? @current-user)
           [:div
            (when-not @retrieving?
              [user-panel current-user state])]))])))
