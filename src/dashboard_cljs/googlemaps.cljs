(ns dashboard-cljs.googlemaps
  (:require [reagent.core :as r]
            ;;[taoensso.timbre :as log :include-macros :true]
            [goog.dom :as dom]
            ;;[cljsjs.google-maps]
            ))

;; code taken from https://gist.github.com/Otann/981553cf03dbb508a96a

(defn create-latlng [{:keys [lat lng]}]
  (js/google.maps.LatLng. lat lng))


;; We store references to Google Maps objects
;; and DOM nodes they are rendered to because there is
;; memory leak in API, which causes serious damage with React
;; See: https://code.google.com/p/gmaps-api-issues/issues/detail?id=3803
(defonce instances (atom {}))

(defn create-cached-gmaps
  "Creates gmaps objects and stores them in cache"
  [id]
  (let [dom (.createElement js/document "div")
        _   (.setAttribute dom "id" (str (name id) "-cached"))
        obj (js/google.maps.Map. dom (clj->js {"zoom" 10}))
        result [dom obj]]
    (swap! instances #(assoc % id result))
    [dom obj]))

(defn get-cached-gmaps
  "Loads objects from cache or creates them and stores in cache then)"
  [id]
  (or
   (@instances id)
   (create-cached-gmaps id)))

(defn gmap
  "Creates map component with reusable instance of Google Maps DOM node
   To ensure clean reusability, please provide unique :id in props
   Usage example:
   [gmap {:id :test
         :style {:height 300}
         :center {:lat -34.397
         :lng 150.644}}]"
  [props]
  (let [[gmaps-dom gmaps-obj] (get-cached-gmaps (:id props "global"))
        update-fn  (fn [this]
                     ;;(log/debug "Updating, component props:" (r/props this))
                     ;;(.log js/console "Updating, component props:" (r/props this))
                     (let [{center :center} (r/props this)
                           latlng (create-latlng center)]
                       ;;(.panTo gmaps-obj latlng)
                       (.setCenter gmaps-obj latlng)))]
    (r/create-class
     {:display-name "gmaps-inner-stateful"

      :reagent-render
      (fn [{style :style id :id}]
        [:div {:style style :id id}])

      :component-did-mount
      (fn [this]
        ;;(log/debug :component-did-mount)
        ;;(.log js/console (js->cl) :component-did-mount)
        (dom/appendChild (r/dom-node this) gmaps-dom)
        (set! (-> gmaps-dom .-style .-width)  "100%")
        (set! (-> gmaps-dom .-style .-height) "100%")
        (js/google.maps.event.trigger gmaps-obj "resize")
        (update-fn this))

      :component-will-unmount
      (fn [this]
        ;;(log/debug :component-will-unmount)
        ;;(.log js/console :)
        (let [canvas (r/dom-node this)]
          (dom/removeChildren canvas)))

      :component-will-receive-props
      (fn [this new-argv]
        ;;(log/debug "Current props" (r/props this))
        ;;(log/debug "Next props" new-argv)
        )

      :component-did-update
      (fn [this]
        ;;(log/debug :component-did-update)
        (update-fn this))

      :set-options
      (fn [this]
        ;;(.log js/console "set-options was called")
        )}
     )))

(defn on-click-tab
  "fn for resetting google map when a tab is clicked"
  []
  ;; reset the google map
  ;; there is a problem with google maps rendering when divs are
  ;; not visible on page
  ;; see: http://stackoverflow.com/questions/10197128/google-maps-api-v3-not-rendering-competely-on-tabbed-page-using-twitters-bootst?lq=1
  ;; http://stackoverflow.com/questions/1428178/problems-with-google-maps-api-v3-jquery-ui-tabs?rq=1
  ;; a call to a function is too fast, must be delayed slightly
  (js/setTimeout
   (fn []
     ;; when google maps does a resize, it
     ;; changes its center ever so-slightly
     ;; therefore, the current center is cached
     ;; see: http://stackoverflow.com/questions/8558226/recenter-a-google-map-after-container-changed-width
     (let [gmap (second (get-cached-gmaps :orders))
           center (.getCenter gmap)]
       (js/google.maps.event.trigger gmap
                                     "resize")
       (.setCenter gmap center)))
   300)
  (js/setTimeout
   (fn []
     (let [gmap (second (get-cached-gmaps :couriers))
           center (.getCenter gmap)]
       (js/google.maps.event.trigger gmap
                                     "resize")
       (.setCenter gmap center)))
   300))
