(ns frontend.components.build-head
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer close!]]
            [clojure.string :as string]
            [frontend.async :refer [raise!]]
            [frontend.analytics :as analytics]
            [frontend.datetime :as datetime]
            [frontend.models.build :as build-model]
            [frontend.models.plan :as plan-model]
            [frontend.models.project :as project-model]
            [frontend.models.feature :as feature]
            [frontend.models.test :as test-model]
            [frontend.components.builds-table :as builds-table]
            [frontend.components.common :as common]
            [frontend.components.forms :as forms]
            [frontend.components.build-config :as build-cfg]
            [frontend.config :refer [intercom-enabled? github-endpoint env enterprise?]]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.timer :as timer]
            [frontend.utils :as utils :include-macros true]
            [frontend.utils.github :as gh-utils]
            [frontend.utils.vcs-url :as vcs-url]
            [frontend.components.build-timings :as build-timings]
            [frontend.components.svg :refer [svg]]
            [goog.dom]
            [goog.dom.DomHelper]
            [goog.string :as gstring]
            [inflections.core :refer (pluralize)]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true])
  (:require-macros [frontend.utils :refer [html defrender inspect]]))

;; This is awful, can't we just pass build-head the whole app state?
;; splitting it up this way means special purpose paths to find stuff
;; in it depending on what sub-state with special keys we have, right?
(defn has-scope [scope data]
  (scope (:scopes data)))

(defn show-additional-containers-offer? [plan build]
  (when (and plan build (not (enterprise?)))
    (let [usage-queued-ms (build-model/usage-queued-time build)
          run-queued-ms (build-model/run-queued-time build)]
      ;; more than 10 seconds waiting for other builds, and
      ;; less than 10 seconds waiting for additional containers (our fault)
      (< run-queued-ms 10000 usage-queued-ms))))

(defn new-additional-containers-offer [plan build]
  (let [run-phrase (if (build-model/finished? build)
                     "ran"
                     "is running")]
    [:div.additional-containers-offer
     [:p
      "This build "
      run-phrase
      " under "
      (:org_name plan)
      "'s plan which provides "
      (plan-model/usable-containers plan)
      " containers, plus 3 additional containers available for free and open source projects. "
      [:a {:href (routes/v1-org-settings-subpage {:org (:org_name plan)
                                                  :subpage "containers"})}
       "Add Containers"]
      " to run more builds concurrently."]]))

(defn queued-explanation-text []
  (if (not (enterprise?))
    "We're sorry; this is our fault. Typically you should only see this when load spikes overwhelm our auto-scaling; waiting to acquire containers should be brief and infrequent."
    [:span
     "Typically, this means you have more builds scheduled to be run than available capacity in the fleet. "
     (if (:admin (utils/current-user))
       [:span "Check " [:a {:href "/admin/fleet-state"} "Fleet State"] " for details and potentially start new builders"]
       "Ask a CircleCI Enterprise administrator to check fleet state and launch new builder machines")]))

(defn build-queue [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [{:keys [build]} data
            build-id (build-model/id build)]
        (raise! owner [:usage-queue-why-showed
                       {:build-id build-id
                        :username (:username @build)
                        :reponame (:reponame @build)
                        :build_num (:build_num @build)}])))

    om/IRender
    (render [_]
      (let [{:keys [build builds]} data
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            plan (:plan data)]
        (html
         (if-not builds
           [:div.loading-spinner common/spinner]
           [:div.build-queue.active
            (when (and (:queued_at build) (not usage-queued?))
              [:p "Circle " (when run-queued? "has") " spent "
               (om/build common/updating-duration {:start (:queued_at build)
                                                   :stop (or (:start_time build) (:stop_time build))})
               " acquiring containers for this build."])
            (when (< 10000 (build-model/run-queued-time build))
              [:p#circle_queued_explanation
               (queued-explanation-text)])

            (when (seq builds)
              (list
               [:p "This build " (if usage-queued? "has been" "was")
                " queued behind the following builds for "
                (om/build common/updating-duration {:start (:usage_queued_at build)
                                                    :stop (or (:queued_at build) (:stop_time build))})
                "."]

               (om/build builds-table/builds-table builds {:opts {:show-actions? true}})))
            (when (show-additional-containers-offer? plan build)
                (new-additional-containers-offer plan build))]))))))

(defn build-queue-v2 [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [{:keys [build]} data
            build-id (build-model/id build)]
        (raise! owner [:usage-queue-why-showed
                       {:build-id build-id
                        :username (:username @build)
                        :reponame (:reponame @build)
                        :build_num (:build_num @build)}])))

    om/IRender
    (render [_]
      (let [{:keys [build builds]} data
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            plan (:plan data)]
        (html
         (if-not builds
           [:div.loading-spinner common/spinner]
           [:div.build-queue.active
            [:div.queue-message
             (when (and (:queued_at build) (not usage-queued?))
               (list
                 "Circle " (when run-queued? "has") " spent "
                 [:strong
                  (om/build common/updating-duration {:start (:queued_at build)
                                                      :stop (or (:start_time build) (:stop_time build))})]
                 " acquiring containers for this build."))

             (when (< 10000 (build-model/run-queued-time build))
               [:span#circle_queued_explanation
                (str " " (queued-explanation-text))])
             (when (seq builds)
               [:span
                " This build " (if usage-queued? "has been" "was")
                " queued behind the following builds for "
                [:strong
                 (om/build common/updating-duration {:start (:usage_queued_at build)
                                                     :stop (or (:queued_at build) (:stop_time build))})]
                (when (show-additional-containers-offer? plan build)
                  (new-additional-containers-offer plan build))])]

              (when (seq builds)
               [:div.queued-builds
                (om/build builds-table/builds-table-v2 builds {:opts {:show-actions? true}})])]))))))

(defn linkify [text]
  (let [url-pattern #"(?im)(\b(https?|ftp)://[-A-Za-z0-9+@#/%?=~_|!:,.;]*[-A-Za-z0-9+@#/%=~_|])"
        pseudo-url-pattern #"(?im)(^|[^/])(www\.[\S]+(\b|$))"]
    (-> text
        ;; TODO: switch to clojure.string/replace when they fix
        ;; http://dev.clojure.org/jira/browse/CLJS-485...
        (.replace (js/RegExp. (.-source url-pattern) "gim")
                  "<a href=\"$1\" target=\"_blank\">$1</a>")
        (.replace (js/RegExp. (.-source pseudo-url-pattern) "gim")
                  "$1<a href=\"http://$2\" target=\"_blank\">$2</a>"))))

(defn maybe-project-linkify [text project-name]
  (if-not project-name
    text
    (let [issue-pattern #"(^|\s)#(\d+)\b"]
      (-> text
          (string/replace issue-pattern
                          (gstring/format "$1<a href='%s/%s/issues/$2' target='_blank'>#$2</a>" (gh-utils/http-endpoint) project-name))))))

(defn commit-line [{:keys [build subject body commit_url commit] :as commit-details} owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (when (seq body)
        (utils/tooltip (str "#commit-line-tooltip-hack-" commit)
                       {:placement "bottom"
                        :animation false
                        :viewport "#build-log-container"})))
    om/IRender
    (render [_]
      (html
       [:div
        [:span.commit-message
         {:title body
          :id (str "commit-line-tooltip-hack-" commit)
          :dangerouslySetInnerHTML {:__html (-> subject
                                                (gstring/htmlEscape)
                                                (linkify)
                                                (maybe-project-linkify (vcs-url/project-name (:vcs_url build))))}}]
        [:a.sha-one {:href commit_url
                     :title commit}
         " "
         (subs commit 0 7)
         " "
         [:i.fa.fa-github]]]))))

(defn build-commits [build-data owner]
  (reify
    om/IRender
    (render [_]
      (let [build (:build build-data)
            build-id (build-model/id build)]
        (html
         [:div.build-commits-container
          [:div.build-commits-title
           (when (:compare build)
             [:a {:href (:compare build)}
              "compare "
              [:i.fa.fa-github]
              " "])
           (when (< 3 (count (:all_commit_details build)))
             [:a {:role "button"
                  :on-click #(raise! owner [:show-all-commits-toggled {:build-id build-id}])}
              (str (- (count (:all_commit_details build)) 3) " more ")
              (if (:show-all-commits? build-data)
                [:i.fa.fa-caret-up]
                [:i.fa.fa-caret-down])])]
          (when (:subject build)
            [:div.build-commits-list
             (if-not (seq (:all_commit_details build))
               (om/build commit-line {:build build
                                      :subject (:subject build)
                                      :body (:body build)
                                      :commit_url (build-model/github-commit-url build)
                                      :commit (:vcs_revision build)})
               (list
                (om/build-all commit-line (take 3 (map #(assoc % :build build)
                                                       (:all_commit_details build))))
                (when (:show-all-commits? build-data)
                  (om/build-all commit-line (drop 3 (map #(assoc % :build build)
                                                         (:all_commit_details build)))))))])])))))

(defn ssh-enabled-note
  "Note that SSH has been enabled for the build, with list of users"
  [[current-user? someone-else?] owner]
  (reify
    om/IRender
    (render [_]
      (html [:p (->> [(when current-user? "you")
                      (when someone-else? "someone other than you")]
                     (filter identity)
                     (string/join " and ")
                     (string/capitalize)
                     (#(str % " enabled SSH for this build.")))]))))

(defn ssh-buttons
  "Show the enable-SSH button(s) for the SSH tab

  Includes the button to SSH to the current build if it is still running, and
  the current user hasn't already enabled SSH for themselves; always shows the
  button to rebuild with SSH enabled (for the current user.)

  Assumes that the user has permission to SSH to the build (=> these should be
  shown)"
  [build owner]
  (reify
    om/IRender
    (render [_]
      (let [build-info {:build-id (build-model/id build)
                        :vcs-url (:vcs_url build)
                        :build-num (:build_num build)}]
        (html
          [:div
           (if-not (build-model/finished? build)
             (forms/managed-button
               [:button.ssh_build
                {:data-loading-text "Adding your SSH keys..."
                 :title "Enable SSH for this build"
                 :on-click #(raise! owner [:ssh-current-build-clicked build-info])}
                "Enable SSH for this build"])
             (forms/managed-button
               [:button.ssh_build
                {:data-loading-text "Starting SSH build..."
                 :title "Retry with SSH in VM"
                 :on-click #(raise! owner [:ssh-build-clicked build-info])}
                "Retry this build with SSH enabled"]))])))))

(defn ssh-ad
  "Note about why you might want to SSH into a build and buttons to do so"
  [build owner]
    [:div.ssh-ad
     [:p "Often the best way to troubleshoot problems is to ssh into a running or finished build to look at log files, running processes, and so on."]
     (om/build ssh-buttons build)
     [:p "This will grant you ssh access to the build's containers, prevent the deploy step from starting, and keep the build up for 30 minutes after it finishes to give you time to investigate."]
     [:p "More information " [:a {:href (routes/v1-doc-subpage {:subpage "ssh-build"})} "in our docs"] "."]])

(defn ssh-ad-v2
  "Note about why you might want to SSH into a build and buttons to do so"
  [build owner]
    [:div.ssh-ad
     [:p
      "Often the best way to troubleshoot problems is to SSH into a running or finished build to look at log files, running processes, and so on.
       This will grant you ssh access to the build's containers, prevent the deploy step from starting, and keep the build up for 30 minutes after it finishes to give you time to investigate.
       More information " [:a {:href (routes/v1-doc-subpage {:subpage "ssh-build"})} "in our docs"] "."
     (om/build ssh-buttons build)]])

(defn ssh-command [node]
  (gstring/format "ssh -p %s %s@%s " (:port node) (:username node) (:public_ip_addr node)))

(defrender ssh-node-list [nodes owner]
  (html
    [:div.build-ssh-list
     [:dl.dl-horizontal
      (map (fn [node i]
             (list
               [:dt (when (< 1 (count nodes)) [:span (str "container " i " ")])]
               [:dd {:class (when (:ssh_enabled node) "connected")}
                [:span (ssh-command node)]
                (when-not (:ssh_enabled node)
                  [:span.loading-spinner common/spinner])]))
           nodes (range))]]))

(defrender ssh-node-list-v2 [nodes owner]
  (html
    [:ul.ssh-nodes-list
     (map-indexed (fn [i node]
                    (let [no-ssh?       (not (:ssh_enabled node))
                          command-class (cond-> "ssh-node-command"
                                          no-ssh? (str " ssh-node-disabled"))]
                      [:li.ssh-node
                       [:span.ssh-node-container (str "Container " i)]
                       [:span {:class command-class} (ssh-command node)]
                       (when no-ssh?
                         (om/build svg {:class "ssh-node-running-icon" :src (common/icon-path "Status-Running")}))]))
                  nodes)]))

(defn ssh-instructions
  "Instructions for SSHing into a build that you can SSH into"
  [build owner]
  (let [nodes (:node build)]
    (html
     [:div.ssh-info-container
      [:div.build-ssh-title
       [:p "You can SSH into this build. Use the same SSH public key that you use for GitHub. SSH boxes will stay up for 30 minutes."]
       [:div
        "This build takes up one of your concurrent builds, so cancel it when you are done. Browser based testing? Read "
        [:a {:href "/docs/browser-debugging#interact-with-the-browser-over-vnc"} "our docs"]
        " on how to use VNC with CircleCI."]]

      (if (feature/enabled? :ui-v2)
        (om/build ssh-node-list-v2 nodes)
        (om/build ssh-node-list nodes))])))

(defn build-ssh [{:keys [build user]} owner]
  (reify
    om/IRender
    (render [_]
      (let [for-current-user? (build-model/current-user-ssh? build user)
            for-someone-else? (build-model/someone-else-ssh? build user)]
        (html
          [:div
           (when (seq (:ssh_users build))
             (om/build ssh-enabled-note [for-current-user? for-someone-else?]))
           (if for-current-user?
             (cond
               (build-model/ssh-enabled-now? build) (ssh-instructions build owner)
               (build-model/finished? build) (if (feature/enabled? :ui-v2)
                                               (ssh-ad-v2 build owner)
                                               (ssh-ad build owner)))
             (if (feature/enabled? :ui-v2)
               (ssh-ad-v2 build owner)
               (ssh-ad build owner)))])))))

(defn cleanup-artifact-path [path]
  (-> path
      (string/replace "$CIRCLE_ARTIFACTS/" "")
      (gstring/truncateMiddle 80)))

(defn artifacts-ad []
  [:div
   [:p "We didn't find any build artifacts for this build. You can upload build artifacts by moving files to the $CIRCLE_ARTIFACTS directory."]
   [:p "Use artifacts for screenshots, coverage reports, deployment tarballs, and more."]
   [:p "More information " [:a {:href (routes/v1-doc-subpage {:subpage "build-artifacts"})} "in our docs"] "."]])

(defn artifacts-tree [prefix artifacts]
  (->> (for [artifact artifacts
             :let [parts (concat [prefix]
                                 (-> (:pretty_path artifact)
                                     (string/split #"/")))]]
         [(vec (remove #{""} parts)) artifact])
       (reduce (fn [acc [parts artifact]]
                 (let [loc (interleave (repeat :children) parts)]
                   (assoc-in acc (concat loc [:artifact]) artifact)))
               {})
       :children))

(defn artifacts-node [{:keys [artifacts show-artifact-links?] :as data} owner opts]
  (reify
    om/IRender
    (render [_]
      (html
       (when (seq artifacts)
         [:ul.build-artifacts-list
          (map-indexed
           (fn node-entry [idx [part {:keys [artifact children]}]]
             (let [directory? (not artifact)
                   text       (if directory?
                                (str part "/")
                                part)
                   url        (:url artifact)
                   tag        (if (and url show-artifact-links?)
                                [:a.artifact-link {:href (:url artifact) :target "_blank"} text]
                                [:span.artifact-directory-text text])
                   key        (keyword (str "index-" idx))
                   closed?    (or
                               (:ancestors-closed? opts)
                               (om/get-state owner [key :closed?]))
                   toggler    (fn [event]
                                (let [key (keyword (str "index-" idx))]
                                  (.preventDefault event)
                                  (.stopPropagation event)
                                  (om/update-state! owner [key :closed?] not)))]
               [:li.build-artifacts-node
                (if directory?
                  [:div.build-artifacts-toggle-children
                   {:style    {:cursor  "pointer"
                               :display "inline"}
                    :on-click toggler}
                   (if closed? "▸  " "▾  ") tag]
                  tag)
                [:div {:style (when closed? {:display "none"})}
                 (om/build artifacts-node
                           {:artifacts children
                            :show-artifact-links? show-artifact-links?}
                           {:opts (assoc opts
                                    :ancestors-closed? (or (:ancestors-closed? opts) closed?))})]]))
           (sort-by first artifacts))])))))

(defn artifacts-node-v2 [{:keys [depth artifacts show-artifact-links?] :as data} owner opts]
  (reify
    om/IRender
    (render [_]
      (html
       (when (seq artifacts)
         [:ul.build-artifacts-list
          (map-indexed
           (fn node-entry [idx [part {:keys [artifact children]}]]
             (let [directory? (not artifact)
                   text       (if (and (not= 0 depth) directory?)
                                (str part "/")
                                part)
                   url        (:url artifact)
                   tag        (if (and url show-artifact-links?)
                                [:a.artifact-link {:href (:url artifact) :target "_blank"} text]
                                [:span.artifact-directory-text text])
                   key        (keyword (str "index-" idx))
                   key-state  (om/get-state owner [key])
                   closed?    (or
                               (:ancestors-closed? opts)
                               (if (contains? key-state :closed?)
                                 (:closed? key-state)
                                 (> depth 1)))
                   toggler    (fn [event]
                                (let [key (keyword (str "index-" idx))]
                                  (.preventDefault event)
                                  (.stopPropagation event)
                                  (om/set-state! owner [key :closed?] (not closed?))))]
               [:li.build-artifacts-node {:class (when (= 0 depth) "container-artifacts")}
                (if directory?
                  [:div.build-artifacts-toggle-children
                   {:style    {:cursor  "pointer"
                               :display "inline"}
                    :on-click toggler}
                   [:i.fa.artifact-toggle-caret
                    {:class (if closed? "fa-angle-right" "fa-angle-down")}]
                   " "
                   tag]
                  tag)
                [:div {:style (when closed? {:display "none"})}
                 (om/build artifacts-node-v2
                           {:depth (+ depth 1)
                            :artifacts children
                            :show-artifact-links? show-artifact-links?}
                           {:opts (assoc opts
                                    :ancestors-closed? (or (:ancestors-closed? opts) closed?))})]]))
           (sort-by first artifacts))])))))

(defn should-show-artifact-links?
  ;; Be extra careful about XSS in production environment
  [env admin?]
  (or (not= env "production")
      (not admin?)))

(defn build-artifacts-list [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (raise! owner [:artifacts-showed]))

    om/IRender
    (render [_]
      (let [artifacts-data (:artifacts-data data)
            artifacts (:artifacts artifacts-data)
            has-artifacts? (:has-artifacts? data)]
        (html
         [:div.build-artifacts-container
          (if-not has-artifacts?
            (artifacts-ad)
            (if artifacts
              (map (fn artifact-node-builder [[node-index node-artifacts]]
                     (om/build artifacts-node {:artifacts (artifacts-tree (str "Container " node-index) node-artifacts)
                                               :show-artifact-links? (should-show-artifact-links? (env) (:admin (:user data)))}))
                   (->> artifacts
                        (group-by :node_index)
                        (sort-by first)))
              [:div.loading-spinner common/spinner]))])))))

(defn build-artifacts-list-v2 [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (raise! owner [:artifacts-showed]))

    om/IRender
    (render [_]
      (let [artifacts-data (:artifacts-data data)
            artifacts (:artifacts artifacts-data)
            has-artifacts? (:has-artifacts? data)]
        (html
         [:div.build-artifacts-container
          (if-not has-artifacts?
            (artifacts-ad)
            (if artifacts
              (interpose [:hr]
                         (map (fn artifact-node-builder [[node-index node-artifacts]]
                                (om/build artifacts-node-v2 {:artifacts (artifacts-tree (str "Container " node-index) node-artifacts)
                                                             :depth 0
                                                             :show-artifact-links? (should-show-artifact-links? (env) (:admin (:user data)))}
                                          ))
                              (->> artifacts
                                   (group-by :node_index)
                                   (sort-by first))))
              [:div.loading-spinner common/spinner]))])))))

(defn tests-ad [owner]
  [:div
   "With some of our inferred build commands we collect test metadata automatically, but that didn't happen for this project.  Collecting metadata allows us to list the specific failures, and in some cases makes parallel builds more efficient.  Here's how to get it:"
   [:ul
    [:li "For an inferred ruby test command, simply add the necessary "
     [:a {:href "/docs/test-metadata#automatic-test-metadata-collection"} "formatter gem"]]
    [:li "Python should work automatically, except for django you'll need to " [:a {:href "/docs/test-metadata#automatic-test-metadata-collection"} "use django-nose"] "."]
    [:li "For another inferred test runner that you'd like us to add metadata support for, "
     [:a (common/contact-support-a-info owner)
      "let us know"] "."]
    [:li "For a custom test command, configure your test runner to write a JUnit XML report to a directory in $CIRCLE_TEST_REPORTS - see "
     [:a {:href "/docs/test-metadata#metadata-collection-in-custom-test-steps"} "the docs"] " for more information."]]])

(defmulti format-test-name-v2 test-model/source)

(defmethod format-test-name-v2 :default [test]
  (->> [[(:name test)] [(:classname test)]]
       (map (fn [s] (some #(when-not (string/blank? %) %) s)))
       (filter identity)
       (string/join " - in ")))

(defmethod format-test-name-v2 "lein-test" [test]
  [:strong.build-test-name (str (:classname test) "/" (:name test))])

(defmethod format-test-name-v2 "cucumber" [test]
  [:strong.build-test-name (if (string/blank? (:name test))
             (:classname test)
             (:name test))])

(defn test-item [test owner]
  (reify
    om/IRender
    (render [_]
      (html
        [:li
         (test-model/format-test-name test)
         (when-not (string/blank? (:message test))
           [:a {:role "button"
                :on-click #(raise! owner [:show-test-message-toggled {:test-index (:i test)}])}
            " more info "
            (if (:show-message test) [:i.fa.fa-caret-up] [:i.fa.fa-caret-down])])
         (when (:show-message test)
           [:pre (:message test)])]))))

(defn test-item-v2 [test owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:li.build-test {:class (when (:show-message test) "expanded")
                        :on-click #(when-not (string/blank? (:message test))
                                     (raise! owner [:show-test-message-toggled {:test-index (:i test)}]))}
        [:span.test-name (format-test-name-v2 test)]
        (when (:show-message test)
           [:pre.build-test-output (:message test)])]))))

(defn build-tests-list [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (raise! owner [:tests-showed]))

    om/IRender
    (render [_]
      (let [tests-data (:tests-data data)
            tests (when (:tests tests-data)
                    (map-indexed #(assoc %2 :i %1) (:tests tests-data)))
            sources (reduce (fn [s test] (conj s (test-model/source test))) #{} tests)
            failed-tests (filter #(contains? #{"failure" "error"} (:result %)) tests)]
        (html
         [:div.build-tests-container
          (if-not tests
            [:div.loading-spinner common/spinner]
            (if (empty? tests)
              (tests-ad owner)
              [:div.build-tests-info
               [:div.build-tests-summary
                (str "Your build ran " (pluralize (count tests) "test") " in "
                     (string/join ", " (map test-model/pretty-source sources))
                     " with " (pluralize (count failed-tests) "failure") ".")]
               (when (seq failed-tests)
                 (for [[source tests-by-source] (group-by test-model/source failed-tests)]
                   [:div.build-tests-list-container
                    [:span.failure-source (str (test-model/pretty-source source) " failures:")]
                    [:ol.build-tests-list
                     (for [[file tests-by-file] (group-by :file tests-by-source)]
                       (list (when file [:div.filename (str file ":")])
                             (om/build-all test-item
                                           (vec (sort-by test-model/format-test-name tests-by-file)))))]]))]))])))))

(def initial-test-render-count 5)

(defn build-tests-file-block [[file failures] owner]
  (reify om/IRender
    (render [_]
      (html
       [:div
        (when file [:li.filename (str file ":")])
        (om/build-all test-item-v2 (vec failures))]))))

(defn build-tests-source-block [[source {:keys [failures successes]}] owner]
  (reify om/IRender
    (render [_]
      (html
       [:div.alert.alert-danger.expanded.build-tests-info
        [:div.alert-header
         [:img.alert-icon {:src (common/icon-path "Info-Error")}]
         [:span.source-name (test-model/pretty-source source)]
         [:span.failure-count (pluralize (count failures) "failure")]]
        [:div.alert-body
         [:div.build-tests-summary
          "Your build ran "
          [:strong (pluralize (+ (count failures)
                                 (count successes)) "test")]
          " with "
          [:strong (pluralize (count failures) "failure")]]
         [:div.build-tests-list-container
          [:ol.list-unstyled.build-tests-list
           (let [file-failures (->> (group-by :file failures)
                                    (map (fn [[k v]] [k (sort-by test-model/format-test-name v)]))
                                    (into (sorted-map)))
                 [top-map bottom-map] (utils/split-map-values-at file-failures initial-test-render-count)]
             (list
              (om/build-all build-tests-file-block top-map)
              (when-not (empty? bottom-map)
                (list
                 [:hr]
                 [:li
                  [:a.build-tests-toggle {:on-click #(om/update-state! owner [:is-open?] not)}
                   [:span
                    [:i.fa.build-tests-toggle-icon {:class (if (om/get-state owner :is-open?) "expanded")}]
                    (if (om/get-state owner :is-open?)
                      "Less"
                      "More")]]]
                 (when (om/get-state owner :is-open?)
                   (om/build-all build-tests-file-block bottom-map))))))]]]]))))

(defn build-tests-list-v2 [{{tests :tests} :tests-data
                            {build-status :status} :build
                            :as data}
                           owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (raise! owner [:tests-showed]))
    om/IRender
    (render [_]
      (let [source-hash (->> tests
                             (map-indexed #(assoc %2 :i %1))
                             (reduce (fn [acc {:keys [result] :as test}]
                                       (update-in acc [(test-model/source test)
                                                       (if (#{"failure" "error"} result)
                                                         :failures
                                                         :successes)]
                                                  #(cons test %)))
                                     {}))
            failed-sources (filter (fn [[_ {:keys [failures]}]]
                                     (seq failures))
                                   source-hash)
            build-succeeded? (= "success" build-status)

            by-time (reverse (sort-by :run_time (filter (comp number? :run_time)
                                                        tests)))
            slowest (first by-time)]
        (html
         [:div.test-results
          (if-not tests
            [:div.loading-spinner common/spinner]
            (cond
              (seq failed-sources) (om/build-all build-tests-source-block failed-sources)
              (seq tests) [:div
                           "Your build ran "
                           [:strong (count tests)]
                           " tests in " (string/join ", " (map test-model/pretty-source (keys source-hash))) " with "
                           [:strong "0 failures"]
                           (when slowest
                             [:div.build-tests-summary
                              [:p
                               [:strong "Slowest test:"]
                               (gstring/format
                                    " %s %s (took %.2f seconds)."
                                    (:classname slowest)
                                    (:name slowest)
                                    (:run_time slowest))]])]
              :else [:div.alert.iconified {:class (if build-succeeded? "alert-info" "alert-danger")}
                     [:div [:img.alert-icon {:src (common/icon-path
                                                   (if build-succeeded? "Info-Info" "Info-Error"))}]]
                     (tests-ad owner)]))])))))

(defn circle-yml-ad []
  [:div
   [:p "We didn't find a circle.yml for this build. You can specify deployment or override our inferred test steps from a circle.yml checked in to your repo's root directory."]
   [:p "More information " [:a {:href (routes/v1-doc-subpage {:subpage "configuration"})} "in our docs"] "."]])

(defn build-config [{:keys [config-string]} owner opts]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [node (om/get-node owner)
            highlight-target (goog.dom.getElementByClass "language-yaml" node)]
        (js/Prism.highlightElement highlight-target)))
    om/IDidUpdate
    (did-update [_ _ _]
      (let [node (om/get-node owner)
            highlight-target (goog.dom.getElementByClass "language-yaml" node)]
        (js/Prism.highlightElement highlight-target)))
    om/IRender
    (render [_]
      (html
       (if (seq config-string)
         [:div.build-config-string [:pre.solarized
                                    [:code.language-yaml config-string]]]
         (circle-yml-ad))))))

(defn build-config-v2 [{:keys [config-string build build-data]} owner opts]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [node (om/get-node owner)
            highlight-target (goog.dom.getElementByClass "config-yml" node)]
        (js/Prism.highlightElement highlight-target)))
    om/IRender
    (render [_]
      (html
       (if (seq config-string)
         [:div
          (when (and (build-model/config-errors? build)
                     (not (:dismiss-config-errors build-data)))
            (om/build build-cfg/config-errors-v2 build))
          [:div.build-config-string [:pre.solarized.line-numbers
                                     [:code.config-yml.language-yaml config-string]]]]
         (circle-yml-ad))))))

(defn build-parameters [{:keys [build-parameters]} owner opts]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.build-parameters
        [:pre (for [[k v] build-parameters
                    :let [pname (name k) pval (pr-str v)]]
                (str pname "=" pval \newline))]]))))

(defn expected-duration
  [{:keys [start stop build]} owner opts]
  (reify

    om/IDisplayName
    (display-name [_] "Expected Duration")

    om/IDidMount
    (did-mount [_]
      (timer/set-updating! owner (not stop)))

    om/IDidUpdate
    (did-update [_ _ _]
      (timer/set-updating! owner (not stop)))

    om/IRender
    (render [_]
      (let [end-ms (if stop
                     (.getTime (js/Date. stop))
                     (datetime/server-now))
            formatter (get opts :formatter datetime/as-duration)
            duration-ms (- end-ms (.getTime (js/Date. start)))
            previous-build (:previous_successful_build build)
            past-ms (:build_time_millis previous-build)]
        (if (and past-ms
                 (= (:status build) "running")
                 (< duration-ms (* 1.5 past-ms)))
          (dom/span nil "/~" (formatter past-ms))
          (dom/span nil ""))))))

(defn default-tab
  "The default tab to show in the build page head, if they have't clicked a different tab."
  [build scopes]
  (cond
   ;; default to ssh-info for SSH builds
   (build-model/ssh-enabled-now? build) :ssh-info
   ;; default to the queue tab if the build is currently usage queued, and
   ;; the user is has the right permissions (and is logged in).
   (and (:read-settings scopes)
        (build-model/in-usage-queue? build))
   :usage-queue
   ;; If there's no SSH info, build isn't finished, show the config or commits.
   (build-model/running? build) (if (feature/enabled? :ui-v2)
                                  ;; "config" takes up too much room for paid customers.
                                  (if (:read-settings scopes)
                                    :usage-queue
                                    :config)
                                  :commits)
   (build-model/config-errors? build) :config
   ;; Otherwise, just use the first one.
   :else :tests))

(def tab-link :a.tab-link)

(defn build-sub-head [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (:build-data data)
            scopes (:scopes data)
            user (:user data)
            logged-in? (not (empty? user))
            admin? (:admin user)
            build (:build build-data)
            selected-tab (or (:selected-header-tab build-data)
                             (default-tab build scopes))
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            usage-queue-data (:usage-queue-data build-data)
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            project (get-in data [:project-data :project])
            plan (get-in data [:project-data :plan])
            config-data (:config-data build-data)
            build-params (:build_parameters build)]
        (html
          [:div.sub-head
           [:div.sub-head-top
            [:ul.nav.nav-tabs
             [:li {:class (when (= :commits selected-tab) "active")}
              [tab-link {:href "#commits"} "Commit Log"]]

            [:li {:class (when (= :config selected-tab) "active")}
             [tab-link {:href "#config"} "circle.yml"]]

            (when (seq build-params)
              [:li {:class (when (= :build-parameters selected-tab) "active")}
               [tab-link {:href "#build-parameters"} "Build Parameters"]])

            (when (has-scope :read-settings data)
              [:li {:class (when (= :usage-queue selected-tab) "active")}
               [tab-link {:id "queued_explanation"
                          :href "#usage-queue"} "Queue"
                (when (:usage_queued_at build)
                  [:span " ("
                   (om/build common/updating-duration {:start (:usage_queued_at build)
                                                       :stop (or (:start_time build) (:stop_time build))})
                   ")"])]])

            ;; XXX Temporarily remove the ssh info for OSX builds
            (when (and (has-scope :write-settings data)
                       (not (project-model/feature-enabled? project :osx)))
              [:li {:class (when (= :ssh-info selected-tab) "active")}
               [tab-link {:href "#ssh-info"} "Debug via SSH"]])

            ;; tests don't get saved until the end of the build (TODO: stream the tests!)
            (when (build-model/finished? build)
              [:li {:class (when (= :tests selected-tab) "active")}
               [tab-link {:href "#tests"} (if (= "success" (:status build))
                                      "Test Results "
                                      "Test Failures ")
                (when-let [fail-count (some->> build-data
                                               :tests-data
                                               :tests
                                               (filter #(contains? #{"failure" "error"} (:result %)))
                                               count)]
                  (when (not= 0 fail-count)
                    [:span {:class "fail-count"} fail-count]))]])

            (when (build-model/finished? build)
              [:li {:class (when (= :build-timing selected-tab) "active")}
               [tab-link {:href "#build-timing"} "Build Timing"]])

            ;; artifacts don't get uploaded until the end of the build (TODO: stream artifacts!)
            (when (and logged-in? (build-model/finished? build))
              [:li {:class (when (= :artifacts selected-tab) "active")}
               [tab-link {:href "#artifacts"} "Artifacts"]])]]

          [:div.sub-head-content
           (case selected-tab
             :commits (om/build build-commits build-data)

             :tests (om/build build-tests-list build-data)

             :build-timing (om/build build-timings/build-timings {:build build
                                                                  :project project
                                                                  :plan plan})

             :artifacts (om/build build-artifacts-list
                                  {:artifacts-data (get build-data :artifacts-data) :user user
                                   :has-artifacts? (:has_artifacts build)})

             :config (om/build build-config {:config-string (get-in build [:circle_yml :string])})

             :build-parameters (om/build build-parameters {:build-parameters build-params})

             :usage-queue (om/build build-queue {:build build
                                                 :builds (:builds usage-queue-data)
                                                 :plan plan})
             :ssh-info (om/build build-ssh {:build build :user user})

             ;; avoid errors if a nonexistent tab is typed in the URL
             nil)]])))))

(defn link-to-user [build]
  (when-let [user (:user build)]
    [:a {:href (gh-utils/login-url (:login user))}
     (build-model/ui-user build)]))

(defn link-to-commit [build]
  [:a {:href (:compare build)}
   (take 7 (:vcs_revision build))])

(defn link-to-retry-source [build]
  (when-let [retry-id (:retry_of build)]
    [:a {:href (gstring/format "/gh/%s/%s/%d"
                               (:username build)
                               (:reponame build)
                               retry-id)}
     retry-id]))

(defn trigger-html [build]
  (let [user-link (link-to-user build)
        commit-link (link-to-commit build)
        retry-link (link-to-retry-source build)
        cache? (build-model/dependency-cache? build)]
    (case (:why build)
      "github" (list user-link " (pushed " commit-link ")")
      "edit" (list user-link " (updated project settings)")
      "first-build" (list user-link " (first build)")
      "retry"  (list user-link " (retried " retry-link
                                (when-not cache? " without cache")")")
      "ssh" (list user-link " (retried " retry-link " with SSH)")
      "auto-retry" (list "CircleCI (auto-retry of " retry-link ")")
      "trigger" (if (:user build)
                  (list user-link " on CircleCI.com")
                  (list "CircleCI.com"))
      "api" "API"
      (or
        (:job_name build)
        "unknown"))))

(defn build-head [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (:build-data data)
            build (:build build-data)
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            usage-queue-data (:usage-queue-data build-data)
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            project (get-in data [:project-data :project])
            plan (get-in data [:project-data :plan])
            user (:user data)
            logged-in? (not (empty? user))
            config-data (:config-data build-data)
            build-info {:build-id (build-model/id build)
                        :vcs-url (:vcs_url build)
                        :build-num (:build_num build)}]
        (html
         [:div.build-head-wrapper
          [:div.build-head
           [:div.build-info
            [:table
             [:tbody
              [:tr
               [:th "Triggered by"]
               [:td (trigger-html build)]
               [:th "Started"]
               [:td (when (:start_time build)
                      {:title (datetime/full-datetime (:start_time build))})
                (when (:start_time build)
                  (list (om/build common/updating-duration
                                  {:start (:start_time build)}
                                  {:opts {:formatter datetime/time-ago}}) " ago"))]]
              [:tr
               [:th "Previous"]
               (if-not (:previous build)
                 [:td "none"]
                 [:td
                  [:a {:href (routes/v1-build-path (vcs-url/org-name vcs-url) (vcs-url/repo-name vcs-url) (:build_num (:previous build)))}
                   (:build_num (:previous build))]])

               [:th "Duration"]
               [:td (if (build-model/running? build)
                      (om/build common/updating-duration {:start (:start_time build)
                                                          :stop (:stop_time build)})
                      (build-model/duration build))
                    (om/build expected-duration {:start (:start_time build)
                                                :stop (:stop_time build)
                                                :build build})]]
              [:tr
               (when (:usage_queued_at build)
                 (list [:th "Queued"]
                       [:td (if (< 0 (build-model/run-queued-time build))
                              [:span
                               (om/build common/updating-duration {:start (:usage_queued_at build)
                                                                   :stop (or (:queued_at build) (:stop_time build))})
                               " waiting + "
                               (om/build common/updating-duration {:start (:queued_at build)
                                                                   :stop (or (:start_time build) (:stop_time build))})
                               " in queue"]

                              [:span
                               (om/build common/updating-duration {:start (:usage_queued_at build)
                                                                   :stop (or (:queued_at build) (:stop_time build))})
                               " waiting for builds to finish"])]))
               [:th "Status"]
               [:td
                [:span.build-status {:class (:status build)}
                 (build-model/status-words build)]
                (when-let [canceler (and (= (:status build) "canceled")
                                         (:canceler build))]
                  [:span.build-canceler
                   (list "by "
                         [:a {:href (str (github-endpoint) "/" (:login canceler))}
                          (if (not-empty (:name canceler))
                            (:name canceler)
                            (:login canceler))])])]]
              [:tr
               [:th "Parallelism"]
               [:td
                [:a.parallelism-link-head {:title (str "This build used " (:parallel build) " containers. Click here to change parallelism for future builds.")
                                           :on-click #(analytics/track-parallelism-build-header-click {})
                                           :href (build-model/path-for-parallelism build)}
                 (str (:parallel build) "x out of "
                      (min (+ (plan-model/usable-containers plan)
                              (if (project-model/oss? project)
                                plan-model/oss-containers
                                0))
                           (plan-model/max-parallelism plan)) "x")]]

               (when-let [urls (seq (:pull_request_urls build))]
                 ;; It's possible for a build to be part of multiple PRs, but it's rare
                 (list [:th (str "PR" (when (< 1 (count urls)) "s"))]
                       [:td
                        (interpose
                         ", "
                         (map (fn [url] [:a {:href url} "#"
                                         (let [n (re-find #"/\d+$" url)]
                                           (if n (subs n 1) "?"))])
                              urls))]))]
            [:tr
             [:th "Author"]
             [:td (if-not (:author_email build)
                    [:span (build-model/author build)]
                    [:a {:href (str "mailto:" (:author_email build))}
                     (build-model/author build)])]
             (when (build-model/author-isnt-committer build)
                 (list [:th "Committer"]
                       [:td
                        (if-not (:committer_email build)
                          [:span (build-model/committer build)]
                          [:a {:href (str "mailto:" (:committer_email build))}
                           (build-model/committer build)])]))]]]
            [:div.build-actions
             (when (has-scope :write-settings data)
               [:div.actions
                (forms/managed-button
                 [:button.retry_build
                  {:data-loading-text "Rebuilding",
                   :title "Retry the same tests",
                   :on-click #(raise! owner [:retry-build-clicked {:build-id build-id
                                                                   :vcs-url vcs-url
                                                                   :build-num build-num
                                                                   :no-cache? false}])}
                  "Rebuild"])

                (forms/managed-button
                 [:button.without_cache_retry
                  {:data-loading-text "Rebuilding",
                   :title "Retry without cache",
                   :on-click #(raise! owner [:retry-build-clicked {:build-id build-id
                                                                   :vcs-url vcs-url
                                                                   :build-num build-num
                                                                   :no-cache? true}])}
                  "without cache"])

                ;; XXX Temporarily remove the ssh button for OSX builds
                (when (not (project-model/feature-enabled? project :osx))
                  (forms/managed-button
                   [:button.ssh_build
                    {:data-loading-text "Rebuilding",
                     :title "Retry with SSH in VM",
                     :on-click #(raise! owner [:ssh-build-clicked {:build-id build-id
                                                                   :vcs-url vcs-url
                                                                   :build-num build-num}])}
                    "with ssh"]))])
             [:div.actions
              ;; TODO: Handle when intercom isn't enabled
              (when (and logged-in? (intercom-enabled?)) ;; no intercom for logged-out users
                [:button.report_build
                 {:title "Report error with build",
                  :on-click #(raise! owner [:report-build-clicked {:build-url (:build_url @build)}])}
                 "Report"])
              (when (and (build-model/can-cancel? build) (has-scope :write-settings data))
                (forms/managed-button
                  [:button.cancel-build
                   {:data-loading-text "Canceling",
                    :title "Cancel this build",
                    :on-click #(raise! owner [:cancel-build-clicked {:build-id build-id
                                                                     :vcs-url vcs-url
                                                                     :build-num build-num}])}
                   "Cancel"]))]]
[:div.no-user-actions]]

           (om/build build-sub-head data)]])))))

(defn commit-line-v2 [{:keys [author_name build subject body commit_url commit] :as commit-details} owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (when (seq body)
        (utils/tooltip (str "#commit-line-tooltip-hack-" commit)
                       {:placement "bottom"
                        :animation false
                        :viewport ".build-commits-container"})))
    om/IRender
    (render [_]
      (html
       [:div.build-commits-list-item
        [:span.metadata-item
         (if-not (:author_email commit-details)
           [:span
            (build-model/author commit-details)]
           [:a {:href (str "mailto:" (:author_email commit-details))}
            (build-model/author commit-details)])]
        (when (build-model/author-isnt-committer commit-details)
          [:span.metadata-item
           (if-not (:committer_email commit-details)
             [:span
              (build-model/committer commit-details)]
             [:a {:href (str "mailto:" (:committer_email commit-details))}
              (build-model/committer commit-details)])])

        [:a.metadata-item.sha-one {:href commit_url
                                   :title commit}
         (subs commit 0 7)]
        [:span.commit-message
         {:title body
          :id (str "commit-line-tooltip-hack-" commit)
          :dangerouslySetInnerHTML {:__html (-> subject
                                                (gstring/htmlEscape)
                                                (linkify)
                                                (maybe-project-linkify (vcs-url/project-name (:vcs_url build))))}}]]))))

(def initial-build-commits-count 3)

(defn build-commits-v2 [{:keys [build show-all-commits?]} owner]
  (reify
    om/IRender
    (render [_]
      (let [build-id (build-model/id build)
            commits (:all_commit_details build)
            [top-commits bottom-commits] (->> commits
                                             (map #(assoc % :build build))
                                             (split-at initial-build-commits-count))]
        (html
         [:div.build-commits-container
          (when (:subject build)
            [:div.build-commits-list
             (if-not (seq commits)
               (om/build commit-line-v2 {:build build
                                         :subject (:subject build)
                                         :body (:body build)
                                         :commit_url (build-model/github-commit-url build)
                                         :commit (:vcs_revision build)})
               (list
                 (om/build-all commit-line-v2 top-commits)
                 (when (< initial-build-commits-count (count commits))
                   (list
                     [:hr]
                     [:a {:class "chevron-label"
                          :role "button"
                          :on-click #(raise! owner [:show-all-commits-toggled])}
                      [:i.fa.rotating-chevron {:class (when show-all-commits? "expanded")}]
                      (if show-all-commits?
                        "Less"
                        "More")]))
                 (when show-all-commits?
                   (om/build-all commit-line-v2 bottom-commits))))])])))))


(def tab-tag :li.build-info-tab)
(def tab-link-v2 :a.tab-link-v2)

(defn build-sub-head-v2 [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (:build-data data)
            scopes (:scopes data)
            user (:user data)
            logged-in? (not (empty? user))
            admin? (:admin user)
            build (:build build-data)
            selected-tab (or (:selected-header-tab build-data)
                             (default-tab build scopes))
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            usage-queue-data (:usage-queue-data build-data)
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            project (get-in data [:project-data :project])
            plan (get-in data [:project-data :plan])
            config-data (:config-data build-data)
            build-params (:build_parameters build)]
        (html
         [:div.sub-head
          [:div.sub-head-top
           [:ul.nav.nav-tabs
            ;; tests don't get saved until the end of the build (TODO: stream the tests!)
            (when (build-model/finished? build)
              [tab-tag {:class (when (= :tests selected-tab) "active")}
               [tab-link-v2 {:href "#tests"} (if (= "success" (:status build))
                                               "Test Results "
                                               "Test Failures ")
                (when-let [fail-count (some->> build-data
                                               :tests-data
                                               :tests
                                               (filter #(contains? #{"failure" "error"} (:result %)))
                                               count)]
                  (when (not= 0 fail-count)
                    [:span "(" fail-count ")"]))]])

            (when (has-scope :read-settings data)
              [tab-tag {:class (when (= :usage-queue selected-tab) "active")}
               [tab-link-v2 {:id "queued_explanation"
                             :href "#usage-queue"} "Queue"
                (when (:usage_queued_at build)
                  [:span " ("
                   (om/build common/updating-duration {:start (:usage_queued_at build)
                                                       :stop (or (:start_time build) (:stop_time build))})
                   ")"])]])

            ;; XXX Temporarily remove the ssh info for OSX builds
            (when (and (has-scope :write-settings data)
                       (not (project-model/feature-enabled? project :osx)))
              [tab-tag {:class (when (= :ssh-info selected-tab) "active")}
               [tab-link-v2 {:href "#ssh-info"}
                "Debug via SSH"]])

            ;; artifacts don't get uploaded until the end of the build (TODO: stream artifacts!)
            (when (and logged-in? (build-model/finished? build))
              [tab-tag {:class (when (= :artifacts selected-tab) "active")}
               [tab-link-v2 {:href "#artifacts"}
                "Artifacts"]])

            [tab-tag {:class (when (= :config selected-tab) "active")}
             [tab-link-v2 {:href "#config"} (str "circle.yml"
                                                 (when-let [errors (-> build build-model/config-errors)]
                                                   (gstring/format " (%s)" (count errors))))]]

            (when (build-model/finished? build)
              [tab-tag {:class (when (= :build-timing selected-tab) "active")}
               [tab-link-v2 {:href "#build-timing"}
                "Build Timing"]])

            (when (seq build-params)
              [tab-tag {:class (when (= :build-parameters selected-tab) "active")}
               [tab-link-v2 {:href "#build-parameters"} "Build Parameters"]])]]

          [:div.card.sub-head-content {:class (str "sub-head-" (name selected-tab))}
           (case selected-tab

             :tests (om/build build-tests-list-v2 build-data)

             :build-timing (om/build build-timings/build-timings {:build build
                                                                  :project project
                                                                  :plan plan})

             :artifacts (om/build build-artifacts-list-v2
                                  {:artifacts-data (get build-data :artifacts-data) :user user
                                   :has-artifacts? (:has_artifacts build)})

             :config (om/build build-config-v2 {:config-string (get-in build [:circle_yml :string])
                                                :build build
                                                :build-data build-data})

             :build-parameters (om/build build-parameters {:build-parameters build-params})

             :usage-queue (om/build build-queue-v2 {:build build
                                                    :builds (:builds usage-queue-data)
                                                    :plan plan})
             :ssh-info (om/build build-ssh {:build build :user user})

             ;; avoid errors if a nonexistent tab is typed in the URL
             nil)]])))))

(defn build-canceler [canceler github-endpoint]
  [:span.summary-label
   (list "Canceled by: "
         [:a  {:href  (str  (github-endpoint) "/"  (:login canceler))}
          (if  (not-empty  (:name canceler))
            (:name canceler)
            (:login canceler))])])

(defn pull-requests [urls]
  ;; It's possible for a build to be part of multiple PRs, but it's rare
  [:div.summary-item
   (when-not (feature/enabled? :ui-v2)
     [:span.summary-spacer "•"])
   [:span.summary-label
    (str (if (feature/enabled? :ui-v2) "PR" "Pull Request")
         (when (< 1 (count urls)) "s")
         ": ")]
   [:span
    (interpose
     ", "
     (map (fn [url] [:a {:href url} "#"
                     (let [n (re-find #"/\d+$" url)]
                       (if n (subs n 1) "?"))])
          urls))]])

(defn queued-time [build]
  (if (< 0 (build-model/run-queued-time build))
    [:span
     (om/build common/updating-duration {:start (:usage_queued_at build)
                                         :stop (or (:queued_at build) (:stop_time build))})
     " waiting + "
     (om/build common/updating-duration {:start (:queued_at build)
                                         :stop (or (:start_time build) (:stop_time build))})
     " in queue"]

    [:span
     (om/build common/updating-duration {:start (:usage_queued_at build)
                                         :stop (or (:queued_at build) (:stop_time build))})
     " waiting for builds to finish"]))

(defn build-running-status [{start-time :start_time
                             :as build}]
  {:pre [(some? start-time)]}
  [:div.summary-item
   [:span.summary-label "Started: "]
   [:span.start-time
    {:title (datetime/full-datetime start-time)}
    (om/build common/updating-duration
              {:start start-time})
    " ago"]])

(defn build-finished-status [{stop-time :stop_time
                              :as build}]
  {:pre [(some? stop-time)]}
  [:div.summary-item
   [:span.summary-label "Finished: "]
   [:span.stop-time
    {:title (datetime/full-datetime stop-time)}
    (om/build common/updating-duration
              {:start stop-time}
              {:opts {:formatter datetime/time-ago-abbreviated}})
    " ago"]
   (str " (" (build-model/duration build) ")")])

(defrender previous-build-label [{:keys [previous] vcs-url :vcs_url} owner]
  (when-let [build-number (:build_num previous)]
    (html
      [:div.summary-item
       [:span.summary-label "Previous: "]
       [:a {:href (routes/v1-build-path (vcs-url/org-name vcs-url) (vcs-url/repo-name vcs-url) build-number)}
          build-number]])))

(defn expected-duration-v2
  [build owner opts]
  (reify
    om/IDisplayName
    (display-name [_] "Expected Duration")

    om/IRender
    (render [_]
      (let [formatter (get opts :formatter datetime/as-duration)
            previous-build (:previous_successful_build build)
            past-ms (:build_time_millis previous-build)]
        (html
         [:div.summary-item
          [:span.summary-label "Estimated: "]
          [:span (formatter past-ms)]])))))

(defn build-head-v2 [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (:build-data data)
            build (:build build-data)
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            usage-queue-data (:usage-queue-data build-data)
            run-queued? (build-model/in-run-queue? build)
            usage-queued? (build-model/in-usage-queue? build)
            project (get-in data [:project-data :project])
            plan (get-in data [:project-data :plan])
            user (:user data)
            logged-in? (not (empty? user))
            config-data (:config-data build-data)
            build-info {:build-id (build-model/id build)
                        :vcs-url (:vcs_url build)
                        :build-num (:build_num build)}]
        (html
         [:div
          [:div.summary-header
           [:div.summary-items
            [:div.summary-item
             (builds-table/build-status-badge build)]
            (if-not (:stop_time build)
              (when (:start_time build)
                (build-running-status build))
              (build-finished-status build))]
           [:div.summary-items
            (when (build-model/running? build)
              (om/build expected-duration-v2 build))
            (om/build previous-build-label build)
            [:div.summary-item
             [:span.summary-label "Parallelism: "]
             [:a.parallelism-link-head {:title (str "This build used " (:parallel build) " containers. Click here to change parallelism for future builds.")
                                        :on-click #(analytics/track-parallelism-build-header-click {})
                                        :href (build-model/path-for-parallelism build)}
              (str (:parallel build) "x out of "
                   (min (+ (plan-model/usable-containers plan)
                           (if (project-model/oss? project)
                             plan-model/oss-containers
                             0))
                        (plan-model/max-parallelism plan)) "x")]]]
           (when (:usage_queued_at build)
             [:div.summary-items
              [:div.summary-item
               [:span.summary-label "Queued: "]
               [:span (queued-time build)]]])

           [:div.summary-build-contents
            [:div.summary-item
             [:span.summary-label "Triggered by: "]
             [:span (trigger-html build)]]

            (when-let [urls (seq (:pull_request_urls build))]
              (pull-requests urls))]]

          (when-let  [canceler  (and  (=  (:status build) "canceled")
                                      (:canceler build))]
            [:div.summary-header
             [:div.summary-items
              [:div.summary-item
               (build-canceler canceler github-endpoint)]]])
          [:div.card
           [:div.small-emphasis "Commits (" (-> build :all_commit_details count) ")"]
           (om/build build-commits-v2 build-data)]
          [:div.build-head-wrapper
           [:div.build-head
            (om/build build-sub-head-v2 data)]]])))))

(defn rebuild-actions-v2 [{:keys [build project]} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:rebuild-status "Rebuild"})

    om/IWillUpdate
    (will-update [_ {:keys [build]} _]
      (when (build-model/running? build)
        (om/set-state! owner [:rebuild-status] "Rebuild")))

    om/IRenderState
    (render-state [_ {:keys [rebuild-status]}]
      (let [rebuild-args    {:build-id  (build-model/id build)
                             :vcs-url   (:vcs_url build)
                             :build-num (:build_num build)}
            update-status!  #(om/set-state! owner [:rebuild-status] %)
            rebuild!        #(raise! owner %)
            actions         {:rebuild
                             {:text  "Rebuild"
                              :title "Retry the same tests"
                              :action #(do (rebuild! [:retry-build-clicked (merge rebuild-args {:no-cache? false})])
                                           (update-status! "Rebuilding..."))}

                             :without_cache
                             {:text  "Rebuild without cache"
                              :title "Retry without cache"
                              :action #(do (rebuild! [:retry-build-clicked (merge rebuild-args {:no-cache? true})])
                                           (update-status! "Rebuilding..."))}

                             :with_ssh
                             {:text  "Rebuild with SSH"
                              :title "Retry with SSH in VM",
                              :action #(do (rebuild! [:ssh-build-clicked rebuild-args])
                                           (update-status! "Rebuilding..."))}}
            text-for    #(-> actions % :text)
            action-for  #(-> actions % :action)]
        (html
         [:div.rebuild-container
          [:button.rebuild {:on-click (action-for :rebuild)}
           [:img.rebuild-icon {:src (utils/cdn-path (str "/img/inner/icons/Rebuild.svg"))}]
           rebuild-status]
          [:span.dropdown.rebuild
           [:i.fa.fa-chevron-down.dropdown-toggle {:data-toggle "dropdown"}]
           [:ul.dropdown-menu.pull-right
            [:li
             [:a {:on-click (action-for :without_cache)} (text-for :without_cache)]]
            ;; XXX Temporarily remove the ssh button for OSX builds
            (when (not (project-model/feature-enabled? project :osx))
              [:li
               [:a {:on-click (action-for :with_ssh)} (text-for :with_ssh)]])]]])))))

(defn build-head-actions
  [data owner]
  (reify
    om/IRender
    (render [_]
      (let [build-data (dissoc (get-in data state/build-data-path) :container-data)
            build (get-in data state/build-path)
            build-id (build-model/id build)
            build-num (:build_num build)
            vcs-url (:vcs_url build)
            project (get-in data state/project-path)
            plan (get-in data state/project-plan-path)
            user (get-in data state/user-path)
            logged-in? (not (empty? user))
            has-write-settings? (:write-settings
                                  (get-in data state/project-scopes-path))]
        (html
          [:div.build-actions-v2
           (when (and (build-model/can-cancel? build) has-write-settings?)
             (forms/managed-button
               [:a.build-action
                {:data-loading-text "Canceling"
                 :title             "Cancel this build"
                 :on-click #(raise! owner [:cancel-build-clicked {:build-id build-id
                                                                  :vcs-url vcs-url
                                                                  :build-num build-num}])}
                "Cancel Build"]))
           (when has-write-settings?
             (om/build rebuild-actions-v2 {:build build :project project}))
           [:a.build-action
            {:href (routes/v1-project-settings (:navigation-data data))}
            [:img.dashboard-icon {:src (common/icon-path "QuickLink-Settings")}]
            "Project Settings"]])))))
