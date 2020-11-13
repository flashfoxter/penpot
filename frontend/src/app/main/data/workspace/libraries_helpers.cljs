;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.libraries-helpers
  (:require
   [cljs.spec.alpha :as s]
   [app.common.spec :as us]
   [app.common.data :as d]
   [app.common.pages-helpers :as cph]
   [app.common.geom.point :as gpt]
   [app.common.pages :as cp]
   [app.util.logging :as log]
   [app.util.text :as ut]))

;; Change this to :info :debug or :trace to debug this module
(log/set-level! :info)

(defonce empty-changes [[] []])

(defonce color-sync-attrs
  [[:fill-color-ref-id   :color    :fill-color]
   [:fill-color-ref-id   :gradient :fill-color-gradient]
   [:fill-color-ref-id   :opacity  :fill-opacity]

   [:stroke-color-ref-id :color    :stroke-color]
   [:stroke-color-ref-id :gradient :stroke-color-gradient]
   [:stroke-color-ref-id :opacity  :stroke-opacity]])

(declare generate-sync-container)
(declare generate-sync-shape)
(declare has-asset-reference-fn)

(declare get-assets)
(declare generate-sync-shape-and-children-components)
(declare generate-sync-shape-and-children-normal)
(declare generate-sync-shape-inverse)
(declare generate-sync-shape-inverse-normal)
(declare generate-sync-shape-inverse-nested)
(declare generate-sync-shape->component)
(declare compare-children)
(declare remove-component-and-ref)
(declare add-shape-to-instance)
(declare add-shape-to-component)
(declare remove-shape)
(declare move-shape)
(declare remove-ref)
(declare reset-touched)
(declare update-attrs)
(declare calc-new-pos)


;; ---- Create a new component ----

(defn make-component-shape
  "Clone the shape and all children. Generate new ids and detach
  from parent and frame. Update the original shapes to have links
  to the new ones."
  [shape objects]
  (assert (nil? (:component-id shape)))
  (assert (nil? (:component-file shape)))
  (assert (nil? (:shape-ref shape)))
  (let [;; Ensure that the component root is not an instance and
        ;; it's no longer tied to a frame.
        update-new-shape (fn [new-shape original-shape]
                           (cond-> new-shape
                             true
                             (-> (assoc :frame-id nil)
                                 (dissoc :component-root?))

                             (nil? (:parent-id new-shape))
                             (dissoc :component-id
                                     :component-file
                                     :shape-ref)))

        ;; Make the original shape an instance of the new component.
        ;; If one of the original shape children already was a component
        ;; instance, maintain this instanceness untouched.
        update-original-shape (fn [original-shape new-shape]
                                (cond-> original-shape
                                  (nil? (:shape-ref original-shape))
                                  (-> (assoc :shape-ref (:id new-shape))
                                      (dissoc :touched))

                                  (nil? (:parent-id new-shape))
                                  (assoc :component-id (:id new-shape)
                                         :component-file nil
                                         :component-root? true)

                                  (some? (:parent-id new-shape))
                                  (dissoc :component-root?)))]

    (cph/clone-object shape nil objects update-new-shape update-original-shape)))

(defn duplicate-component
  "Clone the root shape of the component and all children. Generate new
  ids from all of them."
  [component]
  (let [component-root (cph/get-component-root component)]
    (cph/clone-object component-root
                      nil
                      (get component :objects)
                      identity)))


;; ---- General library synchronization functions ----

(defn generate-sync-file
  "Generate changes to synchronize all shapes in all pages of the current file,
  with the given asset of the given library."
  [asset-type library-id state]
  (s/assert #{:colors :components :typographies} asset-type)
  (s/assert (s/nilable ::us/uuid) library-id)

  (log/info :msg "Sync local file with library"
            :asset-type asset-type
            :library (str (or library-id "local")))

  (let [library-items
        (if (nil? library-id)
          (get-in state [:workspace-data asset-type])
          (get-in state [:workspace-libraries library-id :data asset-type]))]

    (if (empty? library-items)
      empty-changes

      (loop [pages (vals (get-in state [:workspace-data :pages-index]))
             rchanges []
             uchanges []]
        (if-let [page (first pages)]
          (let [[page-rchanges page-uchanges]
                (generate-sync-container asset-type
                                         library-id
                                         state
                                         page
                                         (:id page)
                                         nil)]
            (recur (next pages)
                   (d/concat rchanges page-rchanges)
                   (d/concat uchanges page-uchanges)))
          [rchanges uchanges])))))

(defn generate-sync-library
  "Generate changes to synchronize all shapes inside components of the current
  file library, that use the given type of asset of the given library."
  [asset-type library-id state]

  (log/info :msg "Sync local components with library"
            :asset-type asset-type
            :library (str (or library-id "local")))

  (let [library-items
        (if (nil? library-id)
          (get-in state [:workspace-data asset-type])
          (get-in state [:workspace-libraries library-id :data asset-type]))]
    (if (empty? library-items)
      empty-changes

      (loop [local-components (seq (vals (get-in state [:workspace-data :components])))
             rchanges []
             uchanges []]
        (if-let [local-component (first local-components)]
          (let [[comp-rchanges comp-uchanges]
                (generate-sync-container asset-type
                                         library-id
                                         state
                                         local-component
                                         nil
                                         (:id local-component))]
            (recur (next local-components)
                   (d/concat rchanges comp-rchanges)
                   (d/concat uchanges comp-uchanges)))
          [rchanges uchanges])))))

(defn- generate-sync-container
  "Generate changes to synchronize all shapes in a particular container
  (a page or a component) that are linked to the given library."
  [asset-type library-id state container page-id component-id]

  (if page-id
    (log/debug :msg "Sync page in local file" :page-id page-id)
    (log/debug :msg "Sync component in local library" :component-id component-id))

  (let [has-asset-reference? (has-asset-reference-fn asset-type library-id)
        linked-shapes (cph/select-objects has-asset-reference? container)]
    (loop [shapes (seq linked-shapes)
           rchanges []
           uchanges []]
      (if-let [shape (first shapes)]
        (let [[shape-rchanges shape-uchanges]
              (generate-sync-shape asset-type
                                   library-id
                                   state
                                   (get container :objects)
                                   page-id
                                   component-id
                                   shape)]
          (recur (next shapes)
                 (d/concat rchanges shape-rchanges)
                 (d/concat uchanges shape-uchanges)))
        [rchanges uchanges]))))

(defn- has-asset-reference-fn
  "Gets a function that checks if a shape uses some asset of the given type
  in the given library."
  [asset-type library-id]
  (case asset-type
    :components
    (fn [shape] (and (:component-id shape)
                     (= (:component-file shape) library-id)))

    :colors
    (fn [shape] (if (= (:type shape) :text)
                  (->> shape
                       :content
                       ;; Check if any node in the content has a reference for the library
                       (ut/some-node
                        #(or (and (some? (:stroke-color-ref-id %))
                                  (= library-id (:stroke-color-ref-file %)))
                             (and (some? (:fill-color-ref-id %))
                                  (= library-id (:fill-color-ref-file %))))))
                  (some
                   #(let [attr (name %)
                          attr-ref-id (keyword (str attr "-ref-id"))
                          attr-ref-file (keyword (str attr "-ref-file"))]
                      (and (get shape attr-ref-id)
                           (= library-id (get shape attr-ref-file))))
                   (map #(nth % 2) color-sync-attrs))))

    :typographies
    (fn [shape]
      (and (= (:type shape) :text)
           (->> shape
                :content
                ;; Check if any node in the content has a reference for the library
                (ut/some-node
                 #(and (some? (:typography-ref-id %))
                       (= library-id (:typography-ref-file %)))))))))

(defmulti generate-sync-shape
  "Generate changes to synchronize one shape, that use the given type
  of asset of the given library."
  (fn [type _ _ _ _ _ _ _] type))

(defmethod generate-sync-shape :components
  [_ library-id state objects page-id component-id shape]
  (generate-sync-shape-and-children-components page-id
                                               component-id
                                               (:id shape)
                                               (get state :workspace-data)
                                               (get state :workspace-libraries)
                                               false))

(defn- generate-sync-text-shape [shape page-id component-id update-node]
  (let [old-content (:content shape)
        new-content (ut/map-node update-node old-content)
        rchanges [(d/without-nils {:type :mod-obj
                                   :page-id page-id
                                   :component-id component-id
                                   :id (:id shape)
                                   :operations [{:type :set
                                                 :attr :content
                                                 :val new-content}]})]
        lchanges [(d/without-nils {:type :mod-obj
                                   :page-id page-id
                                   :component-id component-id
                                   :id (:id shape)
                                   :operations [{:type :set
                                                 :attr :content
                                                 :val old-content}]})]]
    (if (= new-content old-content)
      empty-changes
      [rchanges lchanges])))


(defmethod generate-sync-shape :colors
  [_ library-id state _ page-id component-id shape]

  ;; Synchronize a shape that uses some colors of the library. The value of the
  ;; color in the library is copied to the shape.
  (let [colors (get-assets library-id :colors state)]
    (if (= :text (:type shape))
      (let [update-node (fn [node]
                          (if-let [color (get colors (:fill-color-ref-id node))]
                            (assoc node
                                   :fill-color (:color color)
                                   :fill-opacity (:opacity color)
                                   :fill-color-gradient (:gradient color))
                            node))]
        (generate-sync-text-shape shape page-id component-id update-node))
      (loop [attrs (seq color-sync-attrs)
             roperations []
             uoperations []]
        (let [[attr-ref-id color-attr attr] (first attrs)]
          (if (nil? attr)
            (if (empty? roperations)
              empty-changes
              (let [rchanges [(d/without-nils {:type :mod-obj
                                               :page-id page-id
                                               :component-id component-id
                                               :id (:id shape)
                                               :operations roperations})]
                    uchanges [(d/without-nils {:type :mod-obj
                                               :page-id page-id
                                               :component-id component-id
                                               :id (:id shape)
                                               :operations uoperations})]]
                [rchanges uchanges]))
            (if-not (contains? shape attr-ref-id)
              (recur (next attrs)
                     roperations
                     uoperations)
              (let [color (get colors (get shape attr-ref-id))
                    roperation {:type :set
                                :attr attr
                                :val (color-attr color)
                                :ignore-touched true}
                    uoperation {:type :set
                                :attr attr
                                :val (get shape attr)
                                :ignore-touched true}]
                (recur (next attrs)
                       (conj roperations roperation)
                       (conj uoperations uoperation))))))))))

(defmethod generate-sync-shape :typographies
  [_ library-id state _ page-id component-id shape]

  ;; Synchronize a shape that uses some typographies of the library. The attributes
  ;; of the typography are copied to the shape."
  (let [typographies (get-assets library-id :typographies state)
        update-node (fn [node]
                      (if-let [typography (get typographies (:typography-ref-id node))]
                        (merge node (d/without-keys typography [:name :id]))
                        node))]
    (generate-sync-text-shape shape page-id component-id update-node)))


;; ---- Component synchronization helpers ----

(defn- get-assets
  [library-id asset-type state]
  (if (nil? library-id)
    (get-in state [:workspace-data asset-type])
    (get-in state [:workspace-libraries library-id :data asset-type])))

(defn generate-sync-shape-and-children-components
  "Generate changes to synchronize one shape that the root of a component
  instance, and all its children, from the given component.
  If reset? is false, all atributes of each component shape that have
  changed, and whose group has not been touched in the instance shape will
  be copied to this one.
  If reset? is true, all changed attributes will be copied and the 'touched'
  flags in the instance shape will be cleared."
  [page-id component-id shape-id local-file libraries reset?]
  (log/debug :msg "Sync shape and children" :shape (str shape-id) :reset? reset?)
  (let [container      (cph/get-container page-id component-id local-file)
        shape-inst     (cph/get-shape container shape-id)
        component      (cph/get-component (:component-id shape-inst)
                                          (:component-file shape-inst)
                                          local-file
                                          libraries)
        shape-master   (cph/get-shape component (:shape-ref shape-inst))

        root-shape     shape-inst
        root-component (cph/get-component-root component)]

    (generate-sync-shape-and-children-normal page-id
                                             component-id
                                             container
                                             shape-inst
                                             component
                                             shape-master
                                             root-shape
                                             root-component
                                             {:omit-touched? (not reset?)
                                              :reset-touched? reset?
                                              :set-touched? false})))

(defn cons-changes
  [[rchanges uchanges] [rchange uchange]]
  [(cons rchanges rchange)
   (cons uchanges uchange)])

(defn concat-changes
  [[rchanges1 uchanges1] [rchanges2 uchanges2]]
  [(d/concat rchanges1 rchanges2)
   (d/concat uchanges1 uchanges2)])

(defn- generate-sync-shape-and-children-normal
  [page-id component-id container shape-inst component shape-master root-shape root-component options]
  (log/trace :msg "Sync shape (normal)"
             :shape (str (:name shape-inst))
             :component (:name component))

  (let [root-shape (if (:component-id shape-inst)
                     shape-inst
                     root-shape)
        root-component (if (:component-id shape-inst)
                         shape-master
                         root-component)

        [rchanges uchanges]
        (update-attrs shape-inst
                      shape-master
                      root-shape
                      root-component
                      page-id
                      component-id
                      options)

        children-shape     (map #(cph/get-shape container %)
                               (:shapes shape-inst))
        children-component (map #(cph/get-shape component %)
                               (:shapes shape-master))

        only-inst (fn [shape-inst]
                    (js/console.log "only-inst" (clj->js shape-inst))
                    (remove-shape shape-inst
                                  container
                                  page-id))

        only-master (fn [shape-master]
                      (js/console.log "only-master" (clj->js shape-master))
                      (add-shape-to-instance shape-master
                                             component
                                             container))

        both (fn [shape-inst shape-master]
               (js/console.log "both" (clj->js shape-inst) (clj->js shape-master))
               (let [options (if-not (:component-id shape-inst)
                               options
                               {:omit-touched? false
                                :reset-touched? false
                                :set-touched? false
                                :copy-touched? true})]

                 (generate-sync-shape-and-children-normal page-id
                                                          component-id
                                                          container
                                                          shape-inst
                                                          component
                                                          shape-master
                                                          root-shape
                                                          root-component
                                                          options)))

        moved (fn [shape-inst shape-master]
                (move-shape
                  shape-inst
                  (d/index-of children-shape shape-inst)
                  (d/index-of children-component shape-master)
                  container))

        [child-rchanges child-uchanges]
        (compare-children children-shape
                          children-component
                          only-inst
                          only-master
                          both
                          moved)]

    [(d/concat rchanges child-rchanges)
     (d/concat uchanges child-uchanges)]))

(defn- compare-children
  [children-inst children-master only-inst only-master both moved]
  (loop [children-inst (seq (or children-inst []))
         children-master (seq (or children-master []))
         [rchanges uchanges] [[] []]]
    (let [child-inst (first children-inst)
          child-master (first children-master)]
      (cond
        (and (nil? child-inst) (nil? child-master))
        [rchanges uchanges]

        (nil? child-inst)
        (reduce (fn [changes child]
                  (concat-changes changes (only-master child)))
                [rchanges uchanges]
                children-master)

        (nil? child-master)
        (concat-changes [rchanges uchanges]
                        (map only-inst children-inst))

        :else
        (if (cph/is-master-of child-master child-inst)
          (recur (next children-inst)
                 (next children-master)
                 (concat-changes [rchanges uchanges]
                                 (both child-inst child-master)))

          (let [child-inst'   (d/seek #(cph/is-master-of child-master %)
                                      children-inst)
                child-master' (d/seek #(cph/is-master-of % child-inst)
                                      children-master)]
            (cond
              (nil? child-inst')
              (recur children-inst
                     (next children-master)
                     (concat-changes [rchanges uchanges]
                                     (only-master child-master)))

              (nil? child-master')
              (recur (next children-inst)
                     children-master
                     (concat-changes [rchanges uchanges]
                                     (only-inst child-inst)))

              :else
              (recur (remove #(= (:id %) (:id child-inst')) children-inst)
                     (next children-master)
                     (-> [rchanges uchanges]
                         (concat-changes (both child-inst' child-master))
                         (concat-changes (moved child-inst' child-master)))))))))))

(defn- generate-sync-shape-inverse
  "Generate changes to update the component a shape is linked to, from
  the values in the shape and all its children.
  All atributes of each instance shape that have changed, will be copied
  to the component shape. Also clears the 'touched' flags in the source
  shapes.
  And if the component shapes are, in turn, instances of a second component,
  their 'touched' flags will be set accordingly."
  [page-id shape-id local-file libraries]
  (log/debug :msg "Sync inverse shape and children" :shape (str shape-id))
  (let [page           (cph/get-container page-id nil local-file)
        shape          (cph/get-shape page shape-id)
        component      (cph/get-component (:component-id shape)
                                          (:component-file shape)
                                          local-file
                                          libraries)
        root-shape     shape
        root-component (cph/get-component-root component)]

    (generate-sync-shape-inverse-normal page
                                        shape
                                        component
                                        root-shape
                                        root-component)))

(defn- generate-sync-shape-inverse-normal
  [page shape component root-shape root-component]
  (log/trace :msg "Sync shape inverse (normal)"
             :shape (str (:name shape))
             :component (:name component))
  (let [[rchanges uchanges]
        (generate-sync-shape->component shape
                                        component
                                        root-shape
                                        root-component
                                        page)

        children-ids (get shape :shapes [])]

    (loop [children-ids (seq children-ids)
           rchanges rchanges
           uchanges uchanges]
      (let [child-id (first children-ids)]
        (if (nil? child-id)
          [rchanges uchanges]
          (let [child-shape (cph/get-shape page child-id)

                [child-rchanges child-uchanges]
                (if (nil? (:component-id child-shape))
                  (generate-sync-shape-inverse-normal page
                                                      child-shape
                                                      component
                                                      root-shape
                                                      root-component)
                  (generate-sync-shape-inverse-nested page
                                                      child-shape
                                                      component
                                                      root-shape
                                                      root-component))]
            (recur (next children-ids)
                   (d/concat rchanges child-rchanges)
                   (d/concat uchanges child-uchanges))))))))

(defn- generate-sync-shape-inverse-nested
  [page shape component root-shape root-component]
  (log/trace :msg "Sync shape inverse (nested)"
             :shape (str (:name shape))
             :component (:name component))
  (let [component-shape (d/seek #(= (:shape-ref %)
                                    (:shape-ref shape))
                                (vals (:objects component)))
        root-shape (if (:component-id shape)
                     shape
                     root-shape)
        root-component (if (:component-id shape)
                         component-shape
                         root-component)

        [rchanges uchanges]
        (update-attrs component-shape
                      shape
                      root-component
                      root-shape
                      nil
                      (:id component)
                      {:omit-touched? false
                       :reset-touched? false
                       :set-touched? false
                       :copy-touched? true})

        children-ids (get shape :shapes [])]

    (loop [children-ids (seq children-ids)
           rchanges rchanges
           uchanges uchanges]
      (let [child-id (first children-ids)]
        (if (nil? child-id)
          [rchanges uchanges]
          (let [child-shape (cph/get-shape page child-id)

                [child-rchanges child-uchanges]
                (generate-sync-shape-inverse-nested page
                                                    child-shape
                                                    component
                                                    root-shape
                                                    root-component)]
            (recur (next children-ids)
                   (d/concat rchanges child-rchanges)
                   (d/concat uchanges child-uchanges))))))))

(defn- generate-sync-shape->component
  "Generate changes to synchronize one shape inside a component, with other
  shape that is linked to it."
  [shape component root-shape root-component page]
  (if (nil? component)
    empty-changes
    (let [component-shape (get (:objects component) (:shape-ref shape))]
      (let [[rchanges1 uchanges1]
            (if (nil? component-shape)
              (add-shape-to-component shape
                                      component
                                      page)
              (update-attrs component-shape
                            shape
                            root-component
                            root-shape
                            nil
                            (:id root-component)
                            {:omit-touched? false
                             :reset-touched? false
                             :set-touched? true}))
            [rchanges2 uchanges2]
            (reset-touched shape
                           (:id page)
                           nil)]
        [(d/concat rchanges1 rchanges2)
         (d/concat uchanges2 uchanges2)]))))


; ---- Operation generation helpers ----

(defn- add-shape-to-instance
  [component-shape component page]
  (log/info :msg (str "ADD [W] "
                      (:name component-shape)))
  (let [component-parent-shape (cph/get-shape component (:parent-id component-shape))
        parent-shape           (d/seek #(cph/is-master-of component-parent-shape %)
                                       (vals (:objects page)))

        update-new-shape (fn [new-shape original-shape]
                           (assoc new-shape
                                  :shape-ref (:id original-shape)
                                  :frame-id (:frame-id parent-shape)
                                  ;; :component-id (:component-id original-shape)
                                  ;; :component-file (:component-file original-shape)
                                  ;; :component-root? (:component-root? original-shape)
                                  ;; :touched (:touched original-shape)))
                                  ))

        update-original-shape (fn [original-shape new-shape]
                                original-shape)

        [new-shape new-shapes _]
        (cph/clone-object component-shape
                          (:id parent-shape)
                          (get page :objects)
                          update-new-shape
                          update-original-shape)

        rchanges (mapv (fn [shape']
                         {:type :add-obj
                          :id (:id shape')
                          :page-id (:id page)
                          :parent-id (:parent-id shape')
                          :obj shape'})
                       new-shapes)

        uchanges (mapv (fn [shape']
                         {:type :del-obj
                          :id (:id shape')
                          :page-id (:id page)})
                       new-shapes)]

    [rchanges uchanges]))

(defn- add-shape-to-component
  [shape component page]
  (log/info :msg (str "ADD [C] "
                      (:name shape)))
  (let [parent-shape (cph/get-shape page (:parent-id shape))

        update-new-shape (fn [new-shape original-shape]
                           new-shape)

        update-original-shape (fn [original-shape new-shape]
                                (assoc original-shape
                                       :shape-ref (:id new-shape)))

        [new-shape new-shapes updated-shapes]
        (cph/clone-object shape
                          (:shape-ref parent-shape)
                          (get page :objects)
                          update-new-shape
                          update-original-shape)

        rchanges (d/concat
                   (mapv (fn [shape']
                           {:type :add-obj
                            :id (:id shape')
                            :component-id (:id component)
                            :parent-id (:parent-id shape')
                            :obj shape'})
                         new-shapes)
                   (mapv (fn [shape']
                           {:type :mod-obj
                            :page-id (:id page)
                            :id (:id shape')
                            :operations [{:type :set
                                          :attr :component-id
                                          :val (:component-id shape')}
                                         {:type :set
                                          :attr :component-file
                                          :val (:component-file shape')}
                                         {:type :set
                                          :attr :component-root?
                                          :val (:component-root? shape')}
                                         {:type :set
                                          :attr :shape-ref
                                          :val (:shape-ref shape')}
                                         {:type :set
                                          :attr :touched
                                          :val (:touched shape')}]})
                         updated-shapes))

        uchanges (mapv (fn [shape']
                         {:type :del-obj
                          :id (:id shape')
                          :page-id (:id page)})
                       new-shapes)]

    [rchanges uchanges]))

(defn- remove-shape
  [shape container page-id]
  (let [objects    (get container :objects)
        parents    (cph/get-parents (:id shape) objects)
        children   (cph/get-children (:id shape) objects)
        add-change (fn [id]
                     (let [shape' (get objects id)]
                       {:type :add-obj
                        :id id
                        :page-id page-id
                        :index (cph/position-on-parent id objects)
                        :frame-id (:frame-id shape')
                        :parent-id (:parent-id shape')
                        :obj shape'}))

        rchanges [{:type :del-obj
                   :page-id page-id
                   :id (:id shape)}]

        uchanges (d/concat
                   [(add-change (:id shape))]
                   (map add-change children)
                   [{:type :reg-objects
                     :page-id page-id
                     :shapes (vec parents)}])]

    [rchanges uchanges]))

(defn- move-shape
  [shape index-before index-after container]
  (log/info :msg (str "MOVE "
                      (:name shape)
                      " "
                      index-before
                      " -> "
                      index-after))
  (let [rchanges [{:type :mov-objects
                   :parent-id (:parent-id shape)
                   :shapes [(:id shape)]
                   :index index-after
                   :page-id (:id container)}]
        uchanges [{:type :mov-objects
                   :parent-id (:parent-id shape)
                   :shapes [(:id shape)]
                   :index index-before
                   :page-id (:id container)}]]
    [rchanges uchanges]))

(defn- remove-component-and-ref
  [shape page-id component-id]
  [[(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set
                                   :attr :component-root?
                                   :val nil}
                                  {:type :set
                                   :attr :component-id
                                   :val nil}
                                  {:type :set
                                   :attr :component-file
                                   :val nil}
                                  {:type :set
                                   :attr :shape-ref
                                   :val nil}
                                  {:type :set-touched
                                   :touched nil}]})]
   [(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set
                                   :attr :component-root?
                                   :val (:component-root? shape)}
                                  {:type :set
                                   :attr :component-id
                                   :val (:component-id shape)}
                                  {:type :set
                                   :attr :component-file
                                   :val (:component-file shape)}
                                  {:type :set
                                   :attr :shape-ref
                                   :val (:shape-ref shape)}
                                  {:type :set-touched
                                   :touched (:touched shape)}]})]])

(defn- remove-ref
  [shape page-id component-id]
  [[(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set
                                   :attr :shape-ref
                                   :val nil}
                                  {:type :set-touched
                                   :touched nil}]})]
   [(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set
                                   :attr :shape-ref
                                   :val (:shape-ref shape)}
                                  {:type :set-touched
                                   :touched (:touched shape)}]})]])

(defn- reset-touched
  [shape page-id component-id]
  [[(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set-touched
                                   :touched nil}]})]
   [(d/without-nils {:type :mod-obj
                     :id (:id shape)
                     :page-id page-id
                     :component-id component-id
                     :operations [{:type :set-touched
                                   :touched (:touched shape)}]})]])

(defn- update-attrs
  "The main function that implements the sync algorithm. Copy
  attributes that have changed in the origin shape to the dest shape.
  If omit-touched? is true, attributes whose group has been touched
  in the destination shape will be ignored.
  If reset-touched? is true, the 'touched' flags will be cleared in
  the dest shape.
  If set-touched? is true, the corresponding 'touched' flags will be
  set in dest shape if they are different than their current values."
  [dest-shape origin-shape dest-root origin-root page-id component-id
   {:keys [omit-touched? reset-touched? set-touched? copy-touched?]
    :as options :or {omit-touched? false
                     reset-touched? false
                     set-touched? false
                     copy-touched? false}}]

  (log/info :msg (str "SYNC "
                      (:name origin-shape)
                      " -> "
                      (if page-id "[W] " "[C] ")
                      (:name dest-shape)))

  (let [; The position attributes need a special sync algorith, because we do
        ; not synchronize the absolute position, but the position relative of
        ; the container shape of the component.
        new-pos   (calc-new-pos dest-shape origin-shape dest-root origin-root)
        touched   (get dest-shape :touched #{})]

    (loop [attrs (seq (keys (dissoc cp/component-sync-attrs :x :y)))
           roperations (if (or (not= (:x new-pos) (:x dest-shape))
                               (not= (:y new-pos) (:y dest-shape)))
                         [{:type :set :attr :x :val (:x new-pos)}
                          {:type :set :attr :y :val (:y new-pos)}]
                         [])
           uoperations (if (or (not= (:x new-pos) (:x dest-shape))
                               (not= (:y new-pos) (:y dest-shape)))
                         [{:type :set :attr :x :val (:x dest-shape)}
                          {:type :set :attr :y :val (:y dest-shape)}]
                         [])]

      (let [attr (first attrs)]
        (if (nil? attr)
          (let [roperations (cond
                              reset-touched?
                              (conj roperations
                                    {:type :set-touched
                                     :touched nil})
                              copy-touched?
                              (conj roperations
                                    {:type :set-touched
                                     :touched (:touched origin-shape)})
                              :else
                              roperations)

                uoperations (cond
                              (or reset-touched? copy-touched?)
                              (conj uoperations
                                    {:type :set-touched
                                     :touched (:touched dest-shape)})
                              :else
                              uoperations)

                rchanges [(d/without-nils {:type :mod-obj
                                           :id (:id dest-shape)
                                           :page-id page-id
                                           :component-id component-id
                                           :operations roperations})]
                uchanges [(d/without-nils {:type :mod-obj
                                           :id (:id dest-shape)
                                           :page-id page-id
                                           :component-id component-id
                                           :operations uoperations})]]
            [rchanges uchanges])

          (if-not (contains? dest-shape attr)
            (recur (next attrs)
                   roperations
                   uoperations)
            (let [roperation {:type :set
                              :attr attr
                              :val (get origin-shape attr)
                              :ignore-touched (not set-touched?)}
                  uoperation {:type :set
                              :attr attr
                              :val (get dest-shape attr)
                              :ignore-touched (not set-touched?)}

                  attr-group (get cp/component-sync-attrs attr)]
              (if (and (touched attr-group) omit-touched?)
                (recur (next attrs)
                       roperations
                       uoperations)
                (recur (next attrs)
                       (conj roperations roperation)
                       (conj uoperations uoperation))))))))))

(defn- calc-new-pos
  [dest-shape origin-shape dest-root origin-root]
  (let [root-pos        (gpt/point (:x dest-root) (:y dest-root))
        origin-root-pos (gpt/point (:x origin-root) (:y origin-root))
        origin-pos      (gpt/point (:x origin-shape) (:y origin-shape))
        delta           (gpt/subtract origin-pos origin-root-pos)
        shape-pos       (gpt/point (:x dest-shape) (:y dest-shape))
        new-pos         (gpt/add root-pos delta)]
    new-pos))

